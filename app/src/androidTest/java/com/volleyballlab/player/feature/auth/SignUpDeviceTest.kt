package com.volleyballlab.player.feature.auth

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.volleyballlab.player.data.auth.GoogleCredentialClient
import com.volleyballlab.player.data.profile.AssetAddressCatalog
import com.volleyballlab.player.domain.model.*
import com.volleyballlab.player.domain.repository.*
import com.volleyballlab.player.ui.theme.VolleyballLabTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native signup checks with a fake account; never signs out or creates a Firebase user. */
class SignUpDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    @After fun cleanup() { models.clear() }

    @Test fun splitNamesAndOfflineAddressDropdownsWorkAndClearOnCountryChange() {
        val auth = object : AuthRepository {
            override val currentUser = MutableStateFlow<AuthUser?>(null)
            override val currentUid: String? = null
            override suspend fun restoreSession(): AuthUser? = null
            override suspend fun signIn(email: String, password: String, rememberSession: Boolean) = AuthOutcome.Cancelled
            override suspend fun signUp(name: String, email: String, password: String) = AuthOutcome.Cancelled
            override suspend fun signInWithGoogle(idToken: String, rememberSession: Boolean) = AuthOutcome.Cancelled
            override suspend fun sendPasswordReset(email: String) = AuthOutcome.Cancelled
            override suspend fun signOut() = Unit
        }
        val profile = object : ProfileRepository {
            override val profile = MutableStateFlow(AthleteProfile.Anonymous)
            override suspend fun save(profile: AthleteProfile) { this.profile.value = profile }
        }
        val role = object : RoleRepository {
            override val role = MutableStateFlow(UserRole.Player)
            override suspend fun current() = role.value
            override suspend fun setRole(role: UserRole) { this.role.value = role }
        }
        val model = AuthViewModel(auth, profile, role, GoogleCredentialClient("fixture"), AssetAddressCatalog(context.assets))
        models.put("signup", model)
        compose.setContent { VolleyballLabTheme { SignUpScreen({}, {}, model = model) } }
        compose.waitUntil(10_000) { model.state.value.countries.size >= 200 }
        compose.onNodeWithContentDescription("First name").performScrollTo().performTextInput("Kirt Deniel")
        compose.onNodeWithContentDescription("Last name").performScrollTo().performTextInput("Magno")
        assertEquals("Kirt Deniel Magno", model.state.value.name)
        choose("Country", "Philippines", "PH")
        compose.waitUntil(10_000) { model.state.value.areas.isNotEmpty() && !model.state.value.loadingAreas }
        val area = model.state.value.areas.first { it.name == "Cebu" }
        choose("Province / area", area.name, area.id)
        val city = model.state.value.cities.first { it.name == "City of Cebu" }
        choose("City / municipality", city.name, city.id)
        compose.waitUntil(10_000) { model.state.value.barangays.isNotEmpty() && !model.state.value.loadingBarangays }
        val barangay = model.state.value.barangays.first { it.name == "Lahug" }
        choose("Barangay", barangay.name, barangay.id)
        assertEquals("Lahug", model.state.value.address?.barangay?.name)
        compose.onNodeWithContentDescription("Barangay").performScrollTo()
        capture("signup-address-device.png")
        choose("Country", "United States", "US")
        compose.waitUntil(10_000) { !model.state.value.loadingAreas }
        assertNull(model.state.value.area); assertNull(model.state.value.city); assertNull(model.state.value.barangay)
        compose.onNodeWithContentDescription("Barangay").assertDoesNotExist()
        compose.onNodeWithContentDescription("State / province").assertExists()
    }
    private fun choose(label: String, search: String, id: String) {
        compose.onNodeWithContentDescription(label).performScrollTo().performClick().performTextReplacement(search)
        try {
            compose.onNodeWithTag("signup-option-$label-$id").performClick()
        } catch (failure: AssertionError) {
            compose.onRoot(useUnmergedTree = true).printToLog("SignupDropdown")
            capture("signup-dropdown-failure.png")
            throw failure
        }
    }
    private fun capture(name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
