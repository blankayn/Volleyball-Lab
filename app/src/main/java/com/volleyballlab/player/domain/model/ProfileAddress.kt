package com.volleyballlab.player.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AddressOption(val id: String, val name: String)

@Serializable
data class AddressArea(val id: String, val name: String, val cities: List<AddressOption>)

/** Selected administrative address; passwords and precise device location never belong here. */
@Serializable
data class ProfileAddress(
    val country: AddressOption,
    val area: AddressOption? = null,
    val city: AddressOption? = null,
    val barangay: AddressOption? = null,
) {
    fun displayText(): String = listOfNotNull(barangay?.name, city?.name, area?.name, country.name)
        .distinct().joinToString(", ")
}
