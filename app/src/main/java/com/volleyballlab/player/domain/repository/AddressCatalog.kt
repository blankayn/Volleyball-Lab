package com.volleyballlab.player.domain.repository

import com.volleyballlab.player.domain.model.AddressArea
import com.volleyballlab.player.domain.model.AddressOption

interface AddressCatalog {
    suspend fun countries(): List<AddressOption>
    suspend fun areas(countryCode: String): List<AddressArea>
    suspend fun barangays(cityCode: String): List<AddressOption>
}
