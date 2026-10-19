/*
 * Side A's USB audio output: isochronous streaming to a USB Audio Class 1/2 DAC through
 * usbfs, on the file descriptor Android hands out after the user grants USB access.
 *
 * Kotlin claims the interfaces, picks the alternate setting and sets the sample rate; this
 * file only moves samples. PCM is written into a ring buffer; a thread keeps NUM_URBS
 * transfers in flight and refills each one as it completes. For asynchronous DACs the
 * feedback endpoint tells how many samples the DAC really consumes per packet, so the
 * stream follows the DAC's own clock rather than the phone's.
 */
#include <jni.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <android/log.h>

#define TAG "SideA.UsbAudio"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define NUM_URBS 8
#define MAX_SILENCE 64

typedef struct {
    int fd;
    int ep_out, ep_fb;
    int max_packet, fb_packet;
    int packets_per_urb;
    int frame_bytes;
    int fb_interval_scale;      /* microframes per packet, to scale HS per-microframe feedback */
    uint32_t nominal;           /* samples per packet, 16.16 */
    volatile uint32_t current;  /* samples per packet in use, 16.16 */
    uint32_t acc;

    uint8_t *ring;
    size_t ring_size, rd, fill;
    pthread_mutex_t lock;
    pthread_cond_t space;

    uint8_t silence[MAX_SILENCE];
    int silence_len, silence_pos;

    struct usbdevfs_urb *urbs[NUM_URBS];
    struct usbdevfs_urb *fb_urb;
    int outstanding;

    volatile int running;
    volatile int paused;
    volatile int64_t frames_played;
    volatile int underruns;
    volatile int error;
    pthread_t thread;
} stream_t;

static struct usbdevfs_urb *alloc_urb(int packets, int packet_bytes) {
    struct usbdevfs_urb *u = calloc(1, sizeof(*u) + packets * sizeof(struct usbdevfs_iso_packet_desc));
    if (!u) return NULL;
    u->buffer = calloc(packets, packet_bytes);
    u->number_of_packets = packets;
    return u;
}

static void free_urb(struct usbdevfs_urb *u) {
    if (!u) return;
    free(u->buffer);
    free(u);
}

/* Samples for the next packet, from the fractional accumulator. */
static int next_samples(stream_t *s) {
    s->acc += s->current;
    int n = (int)(s->acc >> 16);
    s->acc &= 0xFFFF;
    return n;
}

static void put_silence(stream_t *s, uint8_t *dst, int bytes) {
    for (int i = 0; i < bytes; i++) {
        dst[i] = s->silence[s->silence_pos];
        s->silence_pos = (s->silence_pos + 1) % s->silence_len;
    }
}

/* Fills one OUT transfer from the ring buffer, padding with silence when it runs dry. */
static void fill_urb(stream_t *s, struct usbdevfs_urb *u) {
    uint8_t *buf = u->buffer;
    int total = 0;
    pthread_mutex_lock(&s->lock);
    for (int i = 0; i < u->number_of_packets; i++) {
        int frames = next_samples(s);
        int bytes = frames * s->frame_bytes;
        if (bytes > s->max_packet) bytes = (s->max_packet / s->frame_bytes) * s->frame_bytes;
        int want = bytes;
        int got = 0;
        if (!s->paused) {
            int avail = (int)(s->fill < (size_t)want ? s->fill : (size_t)want);
            avail -= avail % s->frame_bytes;
            while (got < avail) {
                size_t chunk = s->ring_size - s->rd;
                if (chunk > (size_t)(avail - got)) chunk = avail - got;
                memcpy(buf + total + got, s->ring + s->rd, chunk);
                s->rd = (s->rd + chunk) % s->ring_size;
                got += (int)chunk;
            }
            s->fill -= got;
            s->frames_played += got / s->frame_bytes;
            if (got < want && s->frames_played > 0) s->underruns++;
        }
        if (got < want) put_silence(s, buf + total + got, want - got);
        else s->silence_pos = 0; /* keep DoP markers in step after real audio */
        u->iso_frame_desc[i].length = want;
        total += want;
    }
    pthread_cond_broadcast(&s->space);
    pthread_mutex_unlock(&s->lock);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = s->ep_out;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer_length = total;
    u->actual_length = 0;
    u->status = 0;
}

static void prepare_fb(stream_t *s) {
    struct usbdevfs_urb *u = s->fb_urb;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = s->ep_fb;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer_length = s->fb_packet;
    u->iso_frame_desc[0].length = s->fb_packet;
    u->actual_length = 0;
    u->status = 0;
}

/*
 * Feedback comes as 10.14 (full speed, 3 bytes) or 16.16 (high speed, 4 bytes) samples per
 * frame or microframe, and some DACs get the format wrong. Like Linux, take whichever reading
 * lands near the nominal rate.
 */
static void handle_feedback(stream_t *s, const uint8_t *b, int len) {
    if (len < 3) return;
    uint32_t v = b[0] | (b[1] << 8) | (b[2] << 16) | (len >= 4 ? ((uint32_t)b[3] << 24) : 0);
    uint64_t candidates[4] = {
        v, (uint64_t)v << 2, (uint64_t)v * s->fb_interval_scale, ((uint64_t)v << 2) * s->fb_interval_scale,
    };
    for (int i = 0; i < 4; i++) {
        uint64_t c = candidates[i];
        if (c > s->nominal * 3ull / 4 && c < s->nominal * 5ull / 4) {
            s->current = (uint32_t)c;
            return;
        }
    }
}

static void *run(void *arg) {
    stream_t *s = arg;
    while (s->running || s->outstanding > 0) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            s->error = errno;
            LOGW("reap failed: %d", errno);
            break;
        }
        s->outstanding--;
        if (!s->running) continue;
        if (u == s->fb_urb) {
            if (u->status == 0) handle_feedback(s, u->buffer, u->iso_frame_desc[0].actual_length);
            prepare_fb(s);
        } else {
            fill_urb(s, u);
        }
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) {
            s->error = errno;
            LOGW("submit failed: %d", errno);
            break;
        }
        s->outstanding++;
    }
    s->running = 0;
    pthread_mutex_lock(&s->lock);
    pthread_cond_broadcast(&s->space);
    pthread_mutex_unlock(&s->lock);
    return NULL;
}

#define FN(name) Java_io_github_momonekomata_sidea_audio_usb_UsbStream_##name

JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jclass cls, jint fd, jint epOut, jint maxPacket,
        jint epFb, jint fbPacket, jint packetsPerSec, jint fbIntervalScale, jint rate, jint frameBytes, jint ringMs) {
    (void) env; (void) cls;
    stream_t *s = calloc(1, sizeof(*s));
    if (!s) return 0;
    s->fd = fd;
    s->ep_out = epOut;
    s->ep_fb = epFb;
    s->max_packet = maxPacket;
    s->fb_packet = fbPacket > 0 ? fbPacket : 4;
    s->frame_bytes = frameBytes;
    s->fb_interval_scale = fbIntervalScale > 0 ? fbIntervalScale : 1;
    s->nominal = (uint32_t)(((uint64_t)rate << 16) / (uint64_t)packetsPerSec);
    s->current = s->nominal;
    /* About 1 ms of audio per transfer, so 8 ms in flight. */
    s->packets_per_urb = packetsPerSec >= 8000 ? 8 : 1;
    s->ring_size = (size_t)rate * frameBytes * ringMs / 1000;
    s->ring_size -= s->ring_size % frameBytes;
    s->ring = malloc(s->ring_size);
    s->silence_len = frameBytes;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->space, NULL);
    for (int i = 0; i < NUM_URBS; i++) s->urbs[i] = alloc_urb(s->packets_per_urb, maxPacket);
    if (epFb > 0) s->fb_urb = alloc_urb(1, s->fb_packet);
    if (!s->ring) { free(s); return 0; }
    return (jlong)(intptr_t) s;
}

JNIEXPORT jint JNICALL FN(nativeStart)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    s->running = 1;
    if (s->fb_urb) {
        prepare_fb(s);
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, s->fb_urb) == 0) s->outstanding++;
        else LOGW("feedback endpoint refused: %d", errno);
    }
    for (int i = 0; i < NUM_URBS; i++) {
        fill_urb(s, s->urbs[i]);
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            s->error = errno;
            s->running = 0;
            return -errno;
        }
        s->outstanding++;
    }
    if (pthread_create(&s->thread, NULL, run, s) != 0) { s->running = 0; return -1; }
    return 0;
}

JNIEXPORT void JNICALL FN(nativeSetPaused)(JNIEnv *env, jclass cls, jlong h, jboolean paused) {
    (void) env; (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    pthread_mutex_lock(&s->lock);
    s->paused = paused;
    pthread_cond_broadcast(&s->space);
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL FN(nativeFlush)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    pthread_mutex_lock(&s->lock);
    s->rd = 0;
    s->fill = 0;
    pthread_cond_broadcast(&s->space);
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL FN(nativeSetSilence)(JNIEnv *env, jclass cls, jlong h, jbyteArray pattern) {
    (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    jsize n = (*env)->GetArrayLength(env, pattern);
    if (n <= 0 || n > MAX_SILENCE) return;
    pthread_mutex_lock(&s->lock);
    (*env)->GetByteArrayRegion(env, pattern, 0, n, (jbyte *) s->silence);
    s->silence_len = n;
    s->silence_pos = 0;
    pthread_mutex_unlock(&s->lock);
}

/* Blocks until there's room (or 100 ms pass); returns bytes taken, or -errno once the stream died. */
JNIEXPORT jint JNICALL FN(nativeWrite)(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint off, jint len) {
    (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    if (s->error) return -s->error;
    pthread_mutex_lock(&s->lock);
    if (s->fill + s->frame_bytes > s->ring_size && s->running) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 100 * 1000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        pthread_cond_timedwait(&s->space, &s->lock, &ts);
    }
    size_t room = s->ring_size - s->fill;
    size_t n = (size_t) len < room ? (size_t) len : room;
    n -= n % s->frame_bytes;
    size_t wr = (s->rd + s->fill) % s->ring_size;
    size_t done = 0;
    while (done < n) {
        size_t chunk = s->ring_size - wr;
        if (chunk > n - done) chunk = n - done;
        (*env)->GetByteArrayRegion(env, data, off + (jint) done, (jint) chunk, (jbyte *) (s->ring + wr));
        wr = (wr + chunk) % s->ring_size;
        done += chunk;
    }
    s->fill += done;
    pthread_mutex_unlock(&s->lock);
    return (jint) done;
}

JNIEXPORT jlong JNICALL FN(nativeFramesPlayed)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    return ((stream_t *)(intptr_t) h)->frames_played;
}

/* {underruns, feedback samples/packet 16.16, nominal 16.16, error} */
JNIEXPORT jlongArray JNICALL FN(nativeStats)(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    jlong v[4] = { s->underruns, s->current, s->nominal, s->error };
    jlongArray a = (*env)->NewLongArray(env, 4);
    (*env)->SetLongArrayRegion(env, a, 0, 4, v);
    return a;
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    stream_t *s = (stream_t *)(intptr_t) h;
    if (!s) return;
    int started = s->running || s->outstanding > 0;
    s->running = 0;
    for (int i = 0; i < NUM_URBS; i++) if (s->urbs[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    if (s->fb_urb) ioctl(s->fd, USBDEVFS_DISCARDURB, s->fb_urb);
    if (started && s->thread) pthread_join(s->thread, NULL);
    for (int i = 0; i < NUM_URBS; i++) free_urb(s->urbs[i]);
    free_urb(s->fb_urb);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->space);
    free(s->ring);
    free(s);
}

/* Hands an interface back to the kernel's USB audio driver, so Android sees the DAC again. */
JNIEXPORT jint JNICALL FN(nativeReattachKernel)(JNIEnv *env, jclass cls, jint fd, jint iface) {
    (void) env; (void) cls;
    struct usbdevfs_ioctl cmd = { .ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
    int r = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    return r < 0 ? -errno : r; /* 1: a driver bound, 0: none would */
}

/* Resets the DAC: Linux unbinds and probes every interface again, much like a replug. */
JNIEXPORT jint JNICALL FN(nativeReset)(JNIEnv *env, jclass cls, jint fd) {
    (void) env; (void) cls;
    return ioctl(fd, USBDEVFS_RESET, NULL) < 0 ? -errno : 0;
}

/* USB_SPEED_* from the kernel: 1 low, 2 full, 3 high, 5 super. Negative if unknown. */
JNIEXPORT jint JNICALL FN(nativeSpeed)(JNIEnv *env, jclass cls, jint fd) {
    (void) env; (void) cls;
#ifdef USBDEVFS_GET_SPEED
    int r = ioctl(fd, USBDEVFS_GET_SPEED, NULL);
    return r < 0 ? -errno : r;
#else
    (void) fd;
    return -1;
#endif
}
