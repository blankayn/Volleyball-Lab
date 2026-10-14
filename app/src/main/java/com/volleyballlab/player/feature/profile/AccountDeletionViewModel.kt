package com.volleyballlab.player.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.functions.FirebaseFunctionsException
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.data.account.AccountDeletionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class DeletionState(val busy: Boolean = false, val deleted: Boolean = false, val error: String? = null)

class AccountDeletionViewModel(private val service: AccountDeletionService) : ViewModel() {
    val state = MutableStateFlow(DeletionState())
    fun delete(expectedUid: String, password: String?, token: String?) {
        if (state.value.busy || state.value.deleted) return
        state.value = DeletionState(busy = true)
        viewModelScope.launch {
            try { service.delete(expectedUid, password, token); state.value = DeletionState(deleted = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                state.value = DeletionState(error = if (failure is FirebaseFunctionsException && failure.code == FirebaseFunctionsException.Code.NOT_FOUND)
                    "Account deletion is awaiting server activation. Your account has not been deleted."
                else "Deletion did not finish. Check your password and connection, then retry to resume.")
            }
        }
    }
    companion object {
        val Factory = viewModelFactory { initializer { AccountDeletionViewModel(appContainer.accountDeletionService) } }
    }
}
