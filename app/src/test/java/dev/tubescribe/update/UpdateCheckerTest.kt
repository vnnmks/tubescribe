package dev.tubescribe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins GitHub-release parsing and the version comparison used by the updater. */
class UpdateCheckerTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateChecker.isNewer("0.4.1", "0.4.0"))
        assertTrue(UpdateChecker.isNewer("v0.10.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.4.0.1", "0.4.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.99.99"))
    }

    @Test
    fun sameOrOlderVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("v0.4.0", "0.4.0"))
        assertFalse(UpdateChecker.isNewer("0.4.0", "0.4.1"))
        assertFalse(UpdateChecker.isNewer("0.4.0-beta", "0.4.0"))
        assertFalse(UpdateChecker.isNewer("0.4", "0.4.0"))
    }

    @Test
    fun parsesTheApkAssetFromTheLatestRelease() {
        val release = UpdateChecker.parseLatestRelease(
            """
            {
              "tag_name": "v0.4.1",
              "html_url": "https://github.com/vnnmks/tubescribe/releases/tag/v0.4.1",
              "body": "Balanced subtitle lines and a new update screen.",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt",
                  "size": 120
                },
                {
                  "name": "TubeScribe-0.4.1.apk",
                  "browser_download_url": "https://example.com/TubeScribe-0.4.1.apk",
                  "size": 12345678
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("v0.4.1", release?.tag)
        assertEquals("0.4.1", release?.version)
        assertEquals(
            "https://github.com/vnnmks/tubescribe/releases/tag/v0.4.1",
            release?.pageUrl
        )
        assertEquals("Balanced subtitle lines and a new update screen.", release?.notes)
        assertEquals("TubeScribe-0.4.1.apk", release?.apkName)
        assertEquals("https://example.com/TubeScribe-0.4.1.apk", release?.apkUrl)
        assertEquals(12345678L, release?.apkBytes)
    }

    @Test
    fun aReleaseWithoutAnApkStillParses() {
        val release = UpdateChecker.parseLatestRelease(
            """
            {
              "tag_name": "v0.5.0",
              "html_url": "https://github.com/vnnmks/tubescribe/releases/tag/v0.5.0",
              "body": null,
              "assets": []
            }
            """.trimIndent()
        )

        assertEquals("0.5.0", release?.version)
        assertEquals("", release?.notes)
        assertNull(release?.apkUrl)
        assertNull(release?.apkName)
        assertEquals(0L, release?.apkBytes)
    }

    @Test
    fun aReleaseWithoutATagIsRejected() {
        assertNull(UpdateChecker.parseLatestRelease("""{"assets": []}"""))
        assertNull(UpdateChecker.parseLatestRelease("not json"))
    }
}
