package pt.pixellusitano.aguenta.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, asset: String = "aguenta-$tag.apk", body: String = "\"Notas\"") =
        """{"tag_name":"$tag","body":$body,"assets":[{"name":"$asset","browser_download_url":"https://example.org/$asset"}]}"""

    @Test fun codigoDaTag() {
        assertEquals(100, versionCodeFromTag("v0.1.0"))
        assertEquals(10203, versionCodeFromTag("1.2.3"))
        assertNull(versionCodeFromTag("v0.1.0-beta"))
    }

    @Test fun soAvisaQuandoEhMaisRecenteETemApk() {
        assertEquals(NewVersion("v0.1.1", "https://example.org/aguenta-v0.1.1.apk", "Notas"), parseLatest(release("v0.1.1"), 100))
        assertNull(parseLatest(release("v0.1.0"), 100))
        assertNull(parseLatest(release("v0.0.9"), 100))
        assertNull(parseLatest(release("v0.2.0", asset = "notas.txt"), 100))
        assertEquals("", parseLatest(release("v0.2.0", body = "null"), 100)!!.notes)
    }
}
