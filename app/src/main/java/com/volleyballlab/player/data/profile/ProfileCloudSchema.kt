package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.AddressOption
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.ProfileAddress

/** Private identity/address only: no roles, passwords, photos or training records. */
object ProfileCloudSchema {
    const val COLLECTION = "profiles"
    fun fields(profile: AthleteProfile): Map<String, Any?> = mapOf(
        "name" to profile.name.trim().take(AthleteProfile.MAX_NAME_LENGTH),
        "firstName" to profile.firstName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH),
        "lastName" to profile.lastName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH),
        "address" to profile.address?.let { address -> mapOf(
            "country" to option(address.country), "area" to address.area?.let(::option),
            "city" to address.city?.let(::option), "barangay" to address.barangay?.let(::option),
        ) },
    )
    fun seed(profile: AthleteProfile): Map<String, Any?> = fields(profile).filter { (key, value) ->
        if (key == "address") value != null else (value as String).isNotBlank()
    }
    fun patch(before: AthleteProfile, after: AthleteProfile): Map<String, Any?> {
        val old = fields(before)
        return fields(after).filter { (key, value) -> old[key] != value }
    }
    fun overlay(local: AthleteProfile, fields: Map<String, Any?>): AthleteProfile = local.copy(
        name = (fields["name"] as? String) ?: local.name,
        firstName = (fields["firstName"] as? String) ?: local.firstName,
        lastName = (fields["lastName"] as? String) ?: local.lastName,
        address = if (!fields.containsKey("address")) local.address else address(fields["address"]),
    )
    private fun option(value: AddressOption) = mapOf("id" to value.id, "name" to value.name)
    private fun address(value: Any?): ProfileAddress? {
        if (value == null) return null
        val data = value as Map<*, *>
        return ProfileAddress(requireNotNull(readOption(data["country"])), readOption(data["area"]),
            readOption(data["city"]), readOption(data["barangay"]))
    }
    private fun readOption(value: Any?): AddressOption? {
        if (value == null) return null
        val data = value as Map<*, *>
        return AddressOption(data["id"] as String, data["name"] as String)
    }
}
