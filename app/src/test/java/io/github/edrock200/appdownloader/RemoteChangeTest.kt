package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.net.RemoteChange
import io.github.edrock200.appdownloader.net.RemoteFingerprint
import io.github.edrock200.appdownloader.net.RemoteProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChangeTest {

    private fun fp(etag: String? = null, modified: String? = null, length: Long? = null, path: String? = null) =
        RemoteFingerprint(etag, modified, length, path)

    @Test
    fun readsHeaders() {
        val f = RemoteProbe.fromHeaders(
            contentType = "application/vnd.android.package-archive",
            etag = "W/\"abc\"",
            lastModified = "Tue, 03 Nov 2026 18:00:00 GMT",
            contentLength = "1",
            contentRange = "bytes 0-0/7522359",
            finalUrl = "https://cdn.example.com/files/app.apk?token=one-off",
        )!!
        assertEquals("\"abc\"", f.etag)
        assertEquals(7522359L, f.length) // Full size from Content-Range, not the 1-byte body.
        assertEquals("cdn.example.com/files/app.apk", f.path) // Query string dropped.
    }

    @Test
    fun webPagesGiveNoFingerprint() {
        assertNull(RemoteProbe.fromHeaders("text/html; charset=UTF-8", "\"x\"", null, "100", null, "https://x.com/page"))
    }

    @Test
    fun comparesByEtagAndDate() {
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.compare(fp(etag = "a", length = 5), fp(etag = "a", length = 5)))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(etag = "a"), fp(etag = "b")))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(modified = "Mon"), fp(modified = "Tue")))
        // Same ETag but a different size: the file changed.
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(etag = "a", length = 5), fp(etag = "a", length = 6)))
        // When an ETag/date is available, a different address (e.g. another mirror) doesn't count.
        assertEquals(RemoteChange.UNCHANGED, RemoteProbe.compare(fp(etag = "a", path = "m1/x"), fp(etag = "a", path = "m2/x")))
    }

    @Test
    fun weakSignalsOnlyProveChange() {
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(length = 5), fp(length = 6)))
        assertEquals(RemoteChange.CHANGED, RemoteProbe.compare(fp(path = "h/app-1.apk"), fp(path = "h/app-2.apk")))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(fp(length = 5), fp(length = 5)))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(null, fp(etag = "a")))
        assertEquals(RemoteChange.UNKNOWN, RemoteProbe.compare(fp(etag = "a"), null))
    }

    @Test
    fun linkUpdateNeedsInstalledAppAndChange() {
        val e = AppEntry(name = "x", type = SourceType.URL, source = "https://x/a.apk", remoteChange = RemoteChange.CHANGED)
        assertTrue(VersionUtils.linkChanged(e, 5))
        assertFalse(VersionUtils.linkChanged(e, null))
        assertFalse(VersionUtils.linkChanged(e.copy(remoteChange = RemoteChange.UNKNOWN), 5))
        assertTrue(VersionUtils.updateAvailable(e, "1.0", 5))
    }
}
