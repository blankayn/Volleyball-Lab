package com.volleyballlab.player.data.coach

data class ProfileMedia(val photo: String? = null, val banner: String? = null)

internal fun shouldSyncExistingPhoto(hasCloudPhotoField: Boolean, localPath: String?): Boolean =
    !hasCloudPhotoField && !localPath.isNullOrBlank()

internal fun validProfileImage(value: String, banner: Boolean = false): Boolean =
    value.length <= (if (banner) 300_000 else 120_000) &&
        (value.isEmpty() || value.matches(Regex("data:image/jpeg;base64,[A-Za-z0-9+/]+=*")))

/** An explicit removal is an empty string; only missing fields use older sources. */
internal fun resolveProfileMedia(shared: ProfileMedia?, legacy: ProfileMedia?, private: ProfileMedia?) = ProfileMedia(
    photo = shared?.photo ?: legacy?.photo ?: private?.photo,
    banner = shared?.banner ?: legacy?.banner ?: private?.banner,
)
