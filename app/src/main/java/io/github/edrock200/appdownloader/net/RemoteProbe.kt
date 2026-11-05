package io.github.edrock200.appdownloader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/** What the server says about a file, without downloading it. */
@Serializable
data class RemoteFingerprint(
    val etag: String? = null,
    val lastModified: String? = null,
    val length: Long? = null,
    /** Host + path of the final address after redirects (query strings often hold one-off tokens). */
    val path: String? = null,
) {
    val isEmpty get() = etag == null && lastModified == null && length == null && path == null

    /** Stable text form, used to avoid notifying twice about the same change. */
    val key get() = listOf(etag, lastModified, length, path).joinToString("|")
}

@Serializable
enum class RemoteChange { CHANGED, UNCHANGED, UNKNOWN }

object RemoteProbe {

    /**
     * Asks the server for the file's details using a HEAD request, falling back to a GET of
     * a single byte. Returns null when the address leads to a web page or nothing useful is sent.
     */
    suspend fun fingerprint(url: String): RemoteFingerprint? = withContext(Dispatchers.IO) {
        val head = runCatching {
            Http.execute(Request.Builder().url(url).head().build()).use { fromResponse(it) }
        }.getOrNull()
        if (head != null) return@withContext head
        runCatching {
            Http.execute(Request.Builder().url(url).header("Range", "bytes=0-0").get().build()).use { fromResponse(it) }
        }.getOrNull()
    }

    private fun fromResponse(response: Response): RemoteFingerprint? {
        if (!response.isSuccessful) return null
        return fromHeaders(
            contentType = response.header("Content-Type"),
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            contentLength = response.header("Content-Length"),
            contentRange = response.header("Content-Range"),
            finalUrl = response.request.url.toString(),
        )
    }

    /** Builds a fingerprint from response headers (pure, for tests). Web pages give null. */
    fun fromHeaders(
        contentType: String?,
        etag: String?,
        lastModified: String?,
        contentLength: String?,
        contentRange: String?,
        finalUrl: String,
    ): RemoteFingerprint? {
        val type = contentType?.lowercase().orEmpty()
        if (type.startsWith("text/html") || type.startsWith("application/xhtml")) return null
        // "bytes 0-0/12345" gives the full size when only one byte was requested.
        val length = contentRange?.substringAfterLast('/', "")?.toLongOrNull()
            ?: contentLength?.toLongOrNull()?.takeIf { contentRange == null }
        val parsed = finalUrl.toHttpUrlOrNull()
        val fp = RemoteFingerprint(
            etag = etag?.removePrefix("W/")?.trim()?.ifBlank { null },
            lastModified = lastModified?.trim()?.ifBlank { null },
            length = length?.takeIf { it > 0 },
            path = parsed?.let { it.host + it.encodedPath },
        )
        return fp.takeIf { !it.isEmpty }
    }

    /**
     * Compares the fingerprint of the file we last installed ([old]) with what the server
     * reports now ([new]). The ETag and modification date are trusted; size and address are
     * only used when the server sends neither, and then only a difference counts.
     */
    fun compare(old: RemoteFingerprint?, new: RemoteFingerprint?): RemoteChange {
        if (old == null || new == null) return RemoteChange.UNKNOWN
        fun <T> differs(a: T?, b: T?) = a != null && b != null && a != b

        val strongComparable = (old.etag != null && new.etag != null) ||
            (old.lastModified != null && new.lastModified != null)
        if (strongComparable) {
            return if (differs(old.etag, new.etag) || differs(old.lastModified, new.lastModified) ||
                differs(old.length, new.length)
            ) {
                RemoteChange.CHANGED
            } else {
                RemoteChange.UNCHANGED
            }
        }
        if (differs(old.length, new.length) || differs(old.path, new.path)) return RemoteChange.CHANGED
        // A matching size or address alone isn't proof that the file is the same.
        return RemoteChange.UNKNOWN
    }
}
