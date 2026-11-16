package com.godbless.lockin.data

/**
 * The ways an invite code travels: as a web link (tappable in chat apps, opens LockIn if it is
 * installed), as an app link, inside a QR code, or typed by hand. This turns any of them back into
 * the bare 8-character code, and only accepts links that are really ours.
 *
 * [Host] must match the host in AndroidManifest.xml and the page deployed there.
 */
object PairLink {
    const val Host = "lockin.secrets.quest"
    private const val Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun web(code: String) = "https://$Host/join/$code"

    fun app(code: String) = "lockin://pair/$code"

    /** "k7m2-qx9p" becomes "K7M2QX9P". Returns null unless it is a real-looking code. */
    fun normalize(text: String): String? {
        val code = text.filter { it.isLetterOrDigit() }.uppercase()
        return code.takeIf { it.length == 8 && it.all { c -> c in Alphabet } }
    }

    /**
     * Reads a code out of a scanned QR code, a tapped link or pasted text. A plain code is fine; a link
     * must be `https://lockin.secrets.quest/join/CODE` or `lockin://pair/CODE`. Anything else is null.
     */
    fun parse(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return normalize(raw)

        val scheme = raw.substring(0, schemeEnd).lowercase()
        val rest = raw.substring(schemeEnd + 3).substringBefore('#')
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        val host = path.substringBefore('/').lowercase()
        val segments = path.substringAfter('/', "").split('/').filter { it.isNotEmpty() }

        val fromQuery = query.split('&').firstOrNull { it.startsWith("code=") }?.substringAfter('=')
        return when {
            scheme == "https" && host == Host && segments.size == 2 && segments[0] == "join" -> normalize(segments[1])
            scheme == "lockin" && host == "pair" -> normalize(segments.firstOrNull() ?: fromQuery.orEmpty())
            else -> null
        }
    }
}
