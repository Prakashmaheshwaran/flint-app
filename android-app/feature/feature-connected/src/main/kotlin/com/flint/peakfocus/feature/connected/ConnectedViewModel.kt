package com.flint.peakfocus.feature.connected

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class ConnectedViewModel internal constructor(private val repository: ConnectedRepository) : ViewModel() {
    private val state = MutableStateFlow(ConnectedUiState(uid = repository.uid, email = repository.email))
    val uiState: StateFlow<ConnectedUiState> = state.asStateFlow()
    private val stopObserving = repository.observeIdentity {
        val changed = state.value.uid != repository.uid
        reconcileIdentity()
        if (changed && repository.uid != null && !state.value.busy) refresh()
    }

    init { refresh() }

    fun refresh() = action {
        if (repository.email != null) reload()
    }

    fun authenticate(email: String, password: String, create: Boolean) = action {
        repository.authenticate(ConnectedInput.email(email), ConnectedInput.password(password, create), create)
        state.value = ConnectedUiState(uid = repository.uid, email = repository.email, busy = true)
        reload()
    }

    fun resetPassword(email: String) = action {
        repository.resetPassword(ConnectedInput.email(email))
        state.update { it.copy(message = "Reset requested. In local testing, the link appears in the local account service.") }
    }

    fun saveName(name: String) = action {
        repository.saveName(ConnectedInput.name(name))
        reload()
        state.update { it.copy(message = "Profile saved.") }
    }

    fun createGroup(name: String, kind: String) = action {
        val group = repository.createGroup(ConnectedInput.name(name), ConnectedInput.kind(kind))
        state.update { it.copy(selectedGroup = group, members = emptyList(), membersLoaded = false, createdInvite = null) }
        reload()
    }

    fun openGroup(group: ConnectedGroup) = action {
        state.update { it.copy(selectedGroup = group, members = emptyList(), membersLoaded = false, createdInvite = null) }
        reload()
    }

    fun closeGroup() {
        if (state.value.busy) return
        state.update { it.copy(selectedGroup = null, members = emptyList(), membersLoaded = false, createdInvite = null, error = null, message = null) }
    }

    fun createInvite() = action {
        val group = state.value.selectedGroup ?: return@action
        val invite = repository.createInvite(group.id)
        state.update { it.copy(createdInvite = invite, message = "Invite created. The code has not been sent to anyone.") }
    }

    fun previewInvite(code: String) = action {
        val preview = repository.previewInvite(ConnectedInput.invite(code))
        state.update { it.copy(invitePreview = preview) }
    }

    fun acceptInvite() = action {
        val preview = state.value.invitePreview ?: return@action
        val group = repository.acceptInvite(preview.code)
        state.update { it.copy(invitePreview = null, selectedGroup = group, createdInvite = null, members = emptyList(), membersLoaded = false) }
        reload()
        state.update { it.copy(message = "You joined ${group.name}.") }
    }

    fun dismissInvite() {
        if (!state.value.busy) state.update { it.copy(invitePreview = null) }
    }

    fun leaveGroup() = action {
        val group = state.value.selectedGroup ?: return@action
        repository.leaveGroup(group.id)
        state.update { it.copy(selectedGroup = null, members = emptyList(), membersLoaded = false, createdInvite = null) }
        reload()
        state.update { it.copy(message = "You left ${group.name}.") }
    }

    fun signOut() {
        if (state.value.busy) return
        // Hide account-bound data before durable sign-out cleanup can suspend.
        state.value = ConnectedUiState(busy = true)
        viewModelScope.launch {
            try {
                repository.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.value = ConnectedUiState(error = friendlyError(error))
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun clearMessage() { state.update { it.copy(message = null, error = null) } }

    private suspend fun reload() {
        // Commit a complete account snapshot together; partial responses never invent a group.
        val profile = repository.profile()
        val groups = repository.groups()
        val selected = groups.firstOrNull { it.id == state.value.selectedGroup?.id }
        val members = if (selected == null) emptyList() else repository.members(selected.id)
        if (profile.id != repository.uid) throw ConnectedFailure("Your account changed. Please retry.")
        state.update { it.copy(uid = repository.uid, email = repository.email, profile = profile, groups = groups,
            selectedGroup = selected, members = members, loaded = true, membersLoaded = selected != null) }
    }

    private fun reconcileIdentity() { state.update { it.forIdentity(repository.uid, repository.email) } }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

    private fun action(block: suspend () -> Unit) {
        // Set synchronously so repeated taps cannot launch concurrent auth or invite mutations.
        if (state.value.busy) return
        reconcileIdentity()
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(error = friendlyError(error)) }
            } finally {
                reconcileIdentity()
                state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        fun factory(context: Context, config: ConnectedConfig): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ConnectedViewModel::class.java))
                    return ConnectedViewModel(ConnectedRepository(context.applicationContext, config)) as T
                }
            }
    }
}

private fun friendlyError(error: Exception): String = when (error) {
    is ConnectedFailure -> error.message ?: "The request could not be completed."
    is IllegalArgumentException -> error.message ?: "Check the entered details."
    is FirebaseAuthWeakPasswordException -> "Use a stronger password with at least 8 characters."
    is FirebaseAuthUserCollisionException -> "An account with this email exists. Sign in instead."
    is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> "The email or password is incorrect."
    is FirebaseTooManyRequestsException -> "Too many attempts. Please wait and try again."
    is FirebaseNetworkException, is IOException -> "Cannot reach the local account service. Check that it is running, then refresh."
    else -> "The request could not be completed. Please try again."
}
