package com.nuvia.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuviaUpdateManagerTest {

    @Test
    fun acceptsOnlyStableThreePartReleaseTags() {
        assertEquals("0.1.9", stableVersionFromTag("v0.1.9"))
        assertNull(stableVersionFromTag("v.1.0.5"))
        assertNull(stableVersionFromTag("0.1.9"))
        assertNull(stableVersionFromTag("v0.1"))
        assertNull(stableVersionFromTag("v0.1.9-rc1"))
        assertNull(stableVersionFromTag("v01.2.3"))
        assertNull(stableVersionFromTag("v1.02.3"))
        assertNull(stableVersionFromTag("v1.2.03"))
        assertNull(stableVersionFromTag("latest"))
    }

    @Test
    fun comparesNumericVersionComponents() {
        assertTrue(compareVersions("0.1.9", "0.1.8") > 0)
        assertTrue(compareVersions("0.2.0", "0.10.0") < 0)
        assertEquals(0, compareVersions("0.1.8", "0.1.8.0"))
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun malformedTagCannotMasqueradeAsHigherVersion() {
        val malformed = stableVersionFromTag("v.1.0.5")
        assertNull(malformed)
    }
}
