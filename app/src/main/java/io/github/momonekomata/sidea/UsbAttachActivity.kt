package io.github.momonekomata.sidea

import android.app.Activity
import android.os.Bundle

/**
 * Where Android sends "a USB DAC was plugged in". Opening it is what grants Side A access to the
 * DAC (for good, once "Always" is ticked); there's nothing to show, so it closes straight away.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
