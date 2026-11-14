package com.volleyballlab.player.data.profile

import com.volleyballlab.player.domain.model.AddressOption
import com.volleyballlab.player.domain.model.AthleteProfile
import com.volleyballlab.player.domain.model.ProfileAddress

/** Owner-private identity and training preferences; no roles, passwords, or recordings. */
object ProfileCloudSchema {
    const val COLLECTION = "profiles"
    fun fields(profile: AthleteProfile): Map<String, Any?> = mapOf(
        "name" to profile.name.trim().take(AthleteProfile.MAX_NAME_LENGTH),
        "firstName" to profile.firstName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH),
        "lastName" to profile.lastName.trim().take(AthleteProfile.MAX_NAME_PART_LENGTH),
        "position" to profile.position?.name,
        "dominantHand" to profile.dominantHand?.name,
        "goalSkill" to profile.goalSkill?.name,
        "weeklySessionGoal" to profile.weeklySessionGoal,
        "goalText" to profile.goalText.trim().take(160),
        "trainingSetup" to profile.trainingSetup?.let(TrainingSetupCloudSchema::fields),
        "address" to profile.address?.let { address -> mapOf(
            "country" to option(address.country), "area" to address.area?.let(::option),
            "city" to address.city?.let(::option), "barangay" to address.barangay?.let(::option),
        ) },
    )
    fun seed(profile: AthleteProfile): Map<String, Any?> = fields(profile).filter { (key, value) ->
        when (key) {
            "name", "firstName", "lastName", "goalText" -> value is String && value.isNotBlank()
            "weeklySessionGoal" -> profile.trainingSetup?.plan != null || profile.weeklySessionGoal != 3
            else -> value != null
        }
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
        position = if (fields.containsKey("position")) enumValue<com.volleyballlab.player.domain.model.CourtPosition>(fields["position"]) else local.position,
        dominantHand = if (fields.containsKey("dominantHand")) enumValue<com.volleyballlab.player.domain.model.DominantHand>(fields["dominantHand"]) else local.dominantHand,
        goalSkill = if (fields.containsKey("goalSkill")) enumValue<com.volleyballlab.player.domain.model.Skill>(fields["goalSkill"]) else local.goalSkill,
        weeklySessionGoal = (fields["weeklySessionGoal"] as? Number)?.toInt()?.takeIf { it in 1..14 } ?: local.weeklySessionGoal,
        goalText = (fields["goalText"] as? String)?.take(160) ?: local.goalText,
        trainingSetup = if (fields.containsKey("trainingSetup")) TrainingSetupCloudSchema.read(fields["trainingSetup"]) else local.trainingSetup,
    )
    private inline fun <reified T : Enum<T>> enumValue(value: Any?): T? = enumValues<T>().firstOrNull { it.name == value }
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
