package com.portalhacks.frame

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPolicyTest {
    @Test fun normalPlaybackIgnoresAllAmbientReadingsUnlessOptedIn() {
        val policy = LowLightPolicy()
        assertFalse(ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT)
        for (time in 0L..120_000L step 1000) {
            assertFalse(policy.sample(false, if (time % 2000 == 0L) 0f else 1000f, time))
        }
    }

    @Test fun transientDarknessCannotBlankPhotosAndDisablingImmediatelyRestoresThem() {
        val policy = LowLightPolicy()
        assertFalse(policy.sample(true, 0f, 0))
        assertFalse(policy.sample(true, 0f, 14_999))
        assertFalse(policy.sample(true, 12f, 15_000))
        assertFalse(policy.sample(true, 0f, 16_000))
        assertTrue(policy.sample(true, 0f, 31_000))
        assertFalse(policy.sample(false, 0f, 31_001))
        assertFalse(policy.sample(true, 0f, 31_002))
    }

    @Test fun optionalModeRequiresStableLightAndInvalidSensorsFailOpenToPhotos() {
        val policy = LowLightPolicy()
        policy.sample(true, 0f, 0)
        assertTrue(policy.sample(true, 0f, 15_000))
        assertTrue(policy.sample(true, 25f, 16_000))
        assertTrue(policy.sample(true, 25f, 17_999))
        assertFalse(policy.sample(true, 25f, 18_000))
        assertFalse(policy.sample(true, Float.NaN, 19_000))
    }
}
