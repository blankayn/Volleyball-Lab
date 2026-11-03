package com.volleyballlab.player.data.coach

import org.junit.Assert.*
import org.junit.Test

class ProfileMediaTest {
    @Test fun anOlderPhoneOnlyPictureSyncsWhenTheCloudFieldIsMissing() {
        assertTrue(shouldSyncExistingPhoto(false, "/private/profile.jpg"))
        assertFalse(shouldSyncExistingPhoto(false, null))
        assertFalse(shouldSyncExistingPhoto(false, ""))
    }
    @Test fun aCloudPictureOrAnExplicitRemovalPreventsAutomaticReplacement() {
        assertFalse(shouldSyncExistingPhoto(true, "/private/old.jpg"))
    }
    @Test fun webPhotoIsAvailableWhenNoPhoneOrSharedPhotoWasSaved() {
        assertEquals("web-photo", resolveProfileMedia(null, null, ProfileMedia(photo = "web-photo")).photo)
    }
    @Test fun sharedPhotoAndBannerOverrideOlderDeviceIndependentSources() {
        assertEquals(ProfileMedia("new", "cover"), resolveProfileMedia(ProfileMedia("new", "cover"), ProfileMedia("old"), ProfileMedia("private")))
    }
    @Test fun removalDoesNotResurrectAnOlderWebPhotoOrBanner() {
        assertEquals(ProfileMedia("", ""), resolveProfileMedia(ProfileMedia("", ""), ProfileMedia("old"), ProfileMedia("old", "old-banner")))
    }
    @Test fun onlyBoundedJpegDataIsAcceptedAsSharedMedia() {
        assertTrue(validProfileImage("data:image/jpeg;base64,/9j/AAAA"))
        assertTrue(validProfileImage(""))
        assertFalse(validProfileImage("https://private.example/image.jpg"))
        assertFalse(validProfileImage("data:image/svg+xml;base64,AAAA"))
        val banner = "data:image/jpeg;base64," + "A".repeat(130_000)
        assertFalse(validProfileImage(banner))
        assertTrue(validProfileImage(banner, banner = true))
        assertFalse(validProfileImage("data:image/jpeg;base64," + "A".repeat(300_000), banner = true))
    }
}
