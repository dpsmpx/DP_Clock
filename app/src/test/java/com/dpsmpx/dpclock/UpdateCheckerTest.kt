package com.dpsmpx.dpclock

import com.dpsmpx.dpclock.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun parseVersion_plain() {
        assertEquals(listOf(0, 29, 0), UpdateChecker.parseVersion("0.29.0"))
        assertEquals(listOf(1, 2, 10), UpdateChecker.parseVersion("v1.2.10"))
        assertNull(UpdateChecker.parseVersion("0.29.0-dev"))
        assertNull(UpdateChecker.parseVersion(""))
    }

    @Test
    fun isNewer_comparesComponents() {
        assertTrue(UpdateChecker.isNewer("0.29.0", "0.28.2"))
        assertTrue(UpdateChecker.isNewer("0.29.1", "0.29.0"))
        assertFalse(UpdateChecker.isNewer("0.28.2", "0.29.0"))
        assertFalse(UpdateChecker.isNewer("0.29.0", "0.29.0"))
        assertFalse(UpdateChecker.isNewer("0.29.0", "0.29.0-dev"))
    }

    @Test
    fun parseRelease_picksReleaseApkNotDebug() {
        val body = """
            {
              "tag_name": "v0.29.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "browser_download_url": "https://github.com/dpsmpx/DP_Clock/releases/download/v0.29.0/dp-clock-0.29.0-debug.apk"
                },
                {
                  "browser_download_url": "https://github.com/dpsmpx/DP_Clock/releases/download/v0.29.0/dp-clock-0.29.0.apk"
                }
              ]
            }
        """.trimIndent()
        val update = UpdateChecker.parseRelease(body)
        assertNotNull(update)
        assertEquals("0.29.0", update!!.version)
        assertEquals(
            "https://github.com/dpsmpx/DP_Clock/releases/download/v0.29.0/dp-clock-0.29.0.apk",
            update.url,
        )
    }

    @Test
    fun parseRelease_skipsDraft() {
        val body = """{"tag_name":"v1.0.0","draft":true,"prerelease":false,"assets":[]}"""
        assertNull(UpdateChecker.parseRelease(body))
    }
}
