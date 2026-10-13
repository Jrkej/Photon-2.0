package com.photon.app.session

import java.net.URI
import java.net.URISyntaxException

sealed interface JoinTarget {
    data class Code(val code: String) : JoinTarget
    data class Invite(val token: String) : JoinTarget
}

/** Parses what a user types or scans: an 8-character code or an invite link `https://<host>/j/<token>`. */
object InviteLinks {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val TOKEN_CHARS = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun parse(text: String, host: String): JoinTarget? {
        val trimmed = text.trim()
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return inviteToken(trimmed, host)?.let { JoinTarget.Invite(it) }
        }
        return normalizeCode(trimmed)?.let { JoinTarget.Code(it) }
    }

    fun inviteToken(link: String, host: String): String? {
        val uri = try {
            URI(link)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return null
        if (!uri.host.equals(host, ignoreCase = true)) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/j/")) return null
        val token = path.removePrefix("/j/").trimEnd('/')
        return token.takeIf { TOKEN_CHARS.matches(it) }
    }

    /** Uppercases, drops separators, and maps the look-alikes Crockford base32 allows (O, I, L). */
    fun normalizeCode(raw: String): String? {
        val code = raw.uppercase()
            .filterNot { it == '-' || it == ' ' }
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }
            .joinToString("")
        return code.takeIf { it.length == 8 && it.all { c -> c in CROCKFORD } }
    }
}
