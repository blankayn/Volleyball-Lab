package com.volleyballlab.player.data.profile

import android.content.res.AssetManager
import com.volleyballlab.player.domain.model.AddressArea
import com.volleyballlab.player.domain.model.AddressOption
import com.volleyballlab.player.domain.repository.AddressCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Offline public lists, loaded only for the selected country or municipality. */
class AssetAddressCatalog(private val assets: AssetManager) : AddressCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun countries(): List<AddressOption> = read("locations/countries.json")
    override suspend fun areas(countryCode: String): List<AddressArea> {
        require(countryCode.matches(Regex("[A-Z]{2}")))
        return read("locations/countries/$countryCode.json")
    }
    override suspend fun barangays(cityCode: String): List<AddressOption> {
        require(cityCode.matches(Regex("[0-9]{10}")))
        return read("locations/barangays/$cityCode.json")
    }
    private suspend inline fun <reified T> read(path: String): T = withContext(Dispatchers.IO) {
        assets.open(path).bufferedReader().use { json.decodeFromString<T>(it.readText()) }
    }
}
