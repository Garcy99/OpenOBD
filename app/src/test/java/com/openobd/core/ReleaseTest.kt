package com.openobd.core

import com.openobd.core.update.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val json = """
        {"tag_name":"v1.17.0","prerelease":false,"draft":false,"html_url":"https://github.com/Garcy99/OpenOBD/releases/tag/v1.17.0",
         "body":"## What's new since v1.16.1\n• Car screen bridge\n\n## Novedades desde v1.16.1\n• Puente para la pantalla del coche\n\n## Install\nDownload the APK",
         "assets":[{"name":"OpenOBD-v1.17.0.aab","size":24000000,"browser_download_url":"https://example/aab"},
                   {"name":"OpenOBD-v1.17.0.apk","size":13500000,"browser_download_url":"https://github.com/Garcy99/OpenOBD/releases/download/v1.17.0/OpenOBD-v1.17.0.apk"}]}
    """.trimIndent()

    @Test fun `parses the latest release with its APK and notes per language`() {
        val r = Release.parse(json)!!
        assertEquals("1.17.0", r.version)
        assertTrue(r.apkUrl.endsWith("OpenOBD-v1.17.0.apk"))
        assertEquals(13_500_000, r.apkSize)
        assertEquals("• Puente para la pantalla del coche", r.notesFor("es"))
        assertEquals("• Car screen bridge", r.notesFor("de"))
        assertTrue(r.isNewerThan("1.16.1") && r.isNewerThan("1.16.1-debug"))
        assertFalse(r.isNewerThan("1.17.0"))
        assertFalse(r.isNewerThan("1.18"))
    }

    @Test fun `versions compare by number, not as text`() {
        assertTrue(Release.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(Release.compareVersions("v2.0", "1.99.99") > 0)
        assertEquals(0, Release.compareVersions("1.16", "1.16.0"))
        assertEquals(0, Release.compareVersions("1.16.1-debug", "1.16.1"))
    }

    @Test fun `pre-releases and releases without an APK are ignored`() {
        assertNull(Release.parse(json.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Release.parse("""{"tag_name":"v2.0.0","assets":[]}"""))
    }
}
