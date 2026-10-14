package com.volleyballlab.player.feature.auth

import androidx.lifecycle.ViewModelStore
import com.volleyballlab.player.R
import com.volleyballlab.player.data.auth.GoogleCredentialClient
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpFormTest {
    private val ph = AddressOption("PH", "Philippines")
    private val us = AddressOption("US", "United States")
    private val cebu = AddressOption("cebu", "Cebu")
    private val city = AddressOption("cebu-city", "City of Cebu")
    private val barangay = AddressOption("lahug", "Lahug")
    private val auth = FakeAuth()
    private val profiles = FakeProfiles(auth)
    private val roles = object : RoleRepository {
        override val role = MutableStateFlow(UserRole.Player)
        override suspend fun current() = role.value
        override suspend fun setRole(role: UserRole) { this.role.value = role }
    }
    private val catalog = object : AddressCatalog {
        override suspend fun countries() = listOf(ph, us)
        override suspend fun areas(countryCode: String): List<AddressArea> {
            if (countryCode == "PH") delay(100)
            return if (countryCode == "PH") listOf(AddressArea(cebu.id, cebu.name, listOf(city))) else emptyList()
        }
        override suspend fun barangays(cityCode: String) = listOf(barangay)
    }
    private fun checkForm(block: suspend TestScope.(AuthViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val model = AuthViewModel(auth, profiles, roles, GoogleCredentialClient("fixture"), catalog)
        store.put("signup", model)
        try { block(model) } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private fun fill(model: AuthViewModel) {
        model.onFirstNameChange("  Kirt Deniel  ")
        model.onLastNameChange(" Magno ")
        model.onEmailChange("kirt@example.com")
        model.onPasswordChange("valid-password")
        model.onConfirmPasswordChange("valid-password")
        model.onCountryChange(ph)
    }
    @Test fun editingAccountRestoresSelectionsAndSavesOnlyIdentityFields() = checkForm { model ->
        auth.signUp("Kirt Magno", "kirt@example.com", "password")
        profiles.update { it.copy(name = "Kirt Magno", firstName = "Kirt", lastName = "Magno",
            address = ProfileAddress(ph, cebu, city, barangay), position = CourtPosition.Libero,
            photoPath = "private-photo.jpg") }
        model.loadAccount(); advanceUntilIdle()
        assertEquals("Kirt", model.state.value.firstName)
        assertEquals(barangay, model.state.value.barangay)
        assertEquals(listOf(city), model.state.value.cities)
        model.onCountryChange(us); advanceUntilIdle()
        model.onFirstNameChange("  Updated  ")
        var saved = false
        model.saveAccount { saved = true }; advanceUntilIdle()
        assertTrue(saved)
        val profile = profiles.saved.getValue("new-user")
        assertEquals("Updated Magno", profile.name)
        assertEquals(ProfileAddress(us), profile.address)
        assertEquals(CourtPosition.Libero, profile.position)
        assertEquals("private-photo.jpg", profile.photoPath)
        assertEquals(1, auth.registrations)
    }
    @Test fun editingCannotSaveIntoAReplacementAccount() = checkForm { model ->
        auth.signUp("Kirt", "a@example.com", "password")
        profiles.update { it.copy(firstName = "Kirt", lastName = "Magno", address = ProfileAddress(ph)) }
        model.loadAccount(); advanceUntilIdle()
        auth.currentUser.value = AuthUser("different-user", "b@example.com", "B")
        model.saveAccount { fail("Changed account must not save") }; advanceUntilIdle()
        assertFalse(profiles.saved.containsKey("different-user"))
    }
    @Test fun queuedSaveChecksAccountAgainBeforeItWrites() = checkForm { model ->
        auth.signUp("Kirt", "a@example.com", "password")
        profiles.update { it.copy(firstName = "Kirt", lastName = "Magno", address = ProfileAddress(ph)) }
        model.loadAccount(); advanceUntilIdle()
        model.saveAccount { fail("Changed account must not save") }
        auth.currentUser.value = AuthUser("different-user", "b@example.com", "B")
        advanceUntilIdle()
        assertFalse(profiles.saved.containsKey("different-user"))
    }
    @Test fun mismatchedPasswordNeverCreatesAnAccount() = checkForm { model ->
        fill(model); model.onConfirmPasswordChange("different-password")
        model.signUp { fail("Must not authenticate") }; advanceUntilIdle()
        assertEquals(R.string.auth_error_password_mismatch, model.state.value.error)
        assertEquals(0, auth.registrations)
        assertNull(auth.currentUid)
    }
    @Test fun successfulSignUpSavesSplitNameAndAddressOnlyAfterNewUidExists() = checkForm { model ->
        fill(model); advanceUntilIdle()
        model.onAreaChange(cebu); model.onCityChange(city); advanceUntilIdle(); model.onBarangayChange(barangay)
        var completed = 0
        model.signUp { assertEquals(UserRole.Player, it); completed++ }
        model.signUp { fail("Duplicate submit") }; advanceUntilIdle()
        assertEquals(1, auth.registrations)
        assertEquals(1, completed)
        assertEquals("Kirt Deniel Magno", auth.createdName)
        assertEquals(setOf("new-user"), profiles.saved.keys)
        val profile = requireNotNull(profiles.saved["new-user"])
        assertEquals("Kirt Deniel", profile.firstName)
        assertEquals("Magno", profile.lastName)
        assertEquals(ProfileAddress(ph, cebu, city, barangay), profile.address)
        assertFalse(model.state.value.canSubmitSignUp)
    }
    @Test fun countryChangeClearsDescendantsAndCancelsOldCountryLoad() = checkForm { model ->
        model.onCountryChange(ph); runCurrent()
        model.onCountryChange(us); advanceUntilIdle()
        assertEquals(us, model.state.value.country)
        assertTrue(model.state.value.areas.isEmpty())
        model.onCountryChange(ph); advanceUntilIdle()
        model.onAreaChange(cebu); model.onCityChange(city); advanceUntilIdle(); model.onBarangayChange(barangay)
        model.onCountryChange(us)
        assertNull(model.state.value.area); assertNull(model.state.value.city); assertNull(model.state.value.barangay)
        assertTrue(model.state.value.cities.isEmpty()); assertTrue(model.state.value.barangays.isEmpty())
    }
    @Test fun areaChangeClearsCityAndBarangayWithoutRemovingCountry() = checkForm { model ->
        fill(model); advanceUntilIdle()
        model.onAreaChange(cebu); model.onCityChange(city); advanceUntilIdle(); model.onBarangayChange(barangay)
        model.onAreaChange(null)
        assertEquals(ph, model.state.value.country)
        assertNull(model.state.value.city); assertNull(model.state.value.barangay)
    }
    @Test fun loginDoesNotRequireSignupNamesConfirmationOrAddress() = checkForm { model ->
        model.onEmailChange("kirt@example.com"); model.onPasswordChange("valid-password")
        assertTrue(model.state.value.canSubmitLogin)
        assertFalse(model.state.value.canSubmitSignUp)
        assertNotNull(AuthViewModel.validatePasswordConfirmation("valid-password", ""))
        assertNotNull(AuthViewModel.validatePasswordConfirmation("valid-password", "valid-password "))
        assertNull(AuthViewModel.validatePasswordConfirmation("valid-password", "valid-password"))
    }
    private class FakeAuth : AuthRepository {
        override val currentUser = MutableStateFlow<AuthUser?>(null)
        override val currentUid get() = currentUser.value?.uid
        var registrations = 0
        var createdName = ""
        override suspend fun signUp(name: String, email: String, password: String): AuthOutcome {
            registrations++; createdName = name
            currentUser.value = AuthUser("new-user", email, name)
            return AuthOutcome.Success
        }
        override suspend fun restoreSession() = currentUser.value
        override suspend fun signIn(email: String, password: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean) = AuthOutcome.Success
        override suspend fun sendPasswordReset(email: String) = AuthOutcome.Success
        override suspend fun signOut() { currentUser.value = null }
    }
    private class FakeProfiles(private val auth: FakeAuth) : ProfileRepository {
        val saved = mutableMapOf<String, AthleteProfile>()
        override val profile = auth.currentUser.map { saved[it?.uid] ?: AthleteProfile.Anonymous }
        override suspend fun save(profile: AthleteProfile) = update { profile }
        override suspend fun update(transform: (AthleteProfile) -> AthleteProfile) {
            val uid = requireNotNull(auth.currentUid)
            saved[uid] = transform(saved[uid] ?: AthleteProfile.Anonymous)
        }
    }
}
