package app.kingscore.android.core.images

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal fun httpsImageUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return value.takeIf { uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535) }
}

internal fun avatarImageUrl(raw: String?, endpoint: String): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) return httpsImageUrl(value)
    if (value.startsWith('/') || '\\' in value || value.any(Char::isISOControl)) return null
    val segments = value.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    val base = httpsImageUrl(endpoint)?.let(::URI) ?: return null
    val path = segments.joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }
    return base.resolve("/storage/v1/object/public/player-images/$path").toASCIIString()
}

internal fun imageInitials(name: String): String = name.trim().split(Regex("\\s+"))
    .filter(String::isNotBlank).take(2).joinToString("") {
        String(Character.toChars(it.codePointAt(0)))
    }.uppercase(Locale.ITALIAN).ifBlank { "?" }
