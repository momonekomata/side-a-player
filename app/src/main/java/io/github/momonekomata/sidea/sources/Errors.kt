package io.github.momonekomata.sidea.sources

/**
 * An error as a person reads it: libraries wrap one another's messages ("Session.connect:
 * java.io.IOException: End of IO Stream Read"), so the class names and "where" prefixes go,
 * leaving what actually happened.
 */
fun friendlyError(e: Throwable): String {
    if (e.findUnpinned() != null) return "Certificate not trusted yet"
    // The innermost cause usually says it best; the outer ones repeat it with prefixes.
    var root: Throwable = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val raw = (e.message?.takeIf { it.isNotBlank() } ?: root.message?.takeIf { it.isNotBlank() })
        ?: return e.javaClass.simpleName
    return cleanMessage(raw)
}

/** Strips "Session.connect: " and "java.io.IOException: " style prefixes off [raw]. */
fun cleanMessage(raw: String): String {
    var s = raw.trim()
    val prefix = Regex("^(?:[a-z]\\w*\\.)*[A-Za-z]\\w*(?:Exception|Error)?:\\s+|^[A-Za-z]\\w*\\.[a-z]\\w*:\\s+")
    while (true) {
        val m = prefix.find(s) ?: break
        val head = m.value.trimEnd().removeSuffix(":")
        // Only code-ish prefixes: dotted names or exception classes, not "Login failed: …".
        if (!(head.contains('.') || head.endsWith("Exception") || head.endsWith("Error"))) break
        s = s.substring(m.range.last + 1).trimStart()
    }
    return s.ifBlank { raw }.replaceFirstChar { it.uppercaseChar() }
}
