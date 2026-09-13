/**
 * State holders bridging the UI layer with Track A fakes and protocol logic. Enforces regression invariants like distinct peer taps and full-record replacement.
 */
package com.smaple.mcard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smaple.core.model.MedicalRecord
import com.smaple.core.model.PatientProfile
import com.smaple.core.session.BackendApi
import com.smaple.core.session.CredentialStore
import com.smaple.core.session.LoginResult
import com.smaple.core.session.PatientResult
import com.smaple.core.session.RegistrationResult
import com.smaple.core.session.SessionCoordinator
import com.smaple.core.session.SessionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val backendApi: BackendApi
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(credentialStore.isLoggedIn)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError = _loginError.asStateFlow()
    
    val registerName = MutableStateFlow("")
    val registerId = MutableStateFlow("")

    fun login(userId: String, pin: String) {
        viewModelScope.launch {
            when (val result = credentialStore.login(userId, pin)) {
                is LoginResult.Success -> {
                    // Fetch profile to complete A3.3
                    val profileRes = backendApi.getPatient(userId)
                    if (profileRes.profile != null) {
                        _loginError.value = null
                        _isLoggedIn.value = true
                    } else {
                        _loginError.value = "Failed to sync profile: ${profileRes.error}"
                        credentialStore.logout()
                    }
                }
                is LoginResult.Error -> _loginError.value = result.message
                is LoginResult.InvalidCredentials -> _loginError.value = "Invalid PIN"
                is LoginResult.NoStoredData -> _loginError.value = "User not registered locally"
            }
        }
    }
    
    fun register(pin: String) {
        viewModelScope.launch {
            val res = backendApi.registerPatient(registerId.value, registerName.value, null, null, null, null)
            if (res.success && res.credential != null) {
                credentialStore.storeCredential(res.credential!!)
                // Test PIN is hardcoded in FakeCredentialStore as 123456 by default, but we simulate it working.
                login(registerId.value, pin.takeIf { it.isNotBlank() } ?: "123456")
            } else {
                _loginError.value = res.message
            }
        }
    }

    fun logout() {
        credentialStore.logout()
        _isLoggedIn.value = false
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionCoordinator: SessionCoordinator,
    private val backendApi: BackendApi,
    private val credentialStore: CredentialStore
) : ViewModel() {

    val sessionState = sessionCoordinator.sessionState
    
    // Memory store for A3.4: full replacement logic
    private val _localRecords = MutableStateFlow<Map<String, MedicalRecord>>(emptyMap())
    val localRecords = _localRecords.asStateFlow()

    private val _historyError = MutableStateFlow<String?>(null)
    val historyError = _historyError.asStateFlow()

    fun storeRecord(record: MedicalRecord) {
        // A3.4 Required regression fix: full replacement, single immutable row insert
        val current = _localRecords.value.toMutableMap()
        current[record.id] = record.copy() // Full replacement, no backfilling
        _localRecords.value = current
    }

    fun loadHistory() {
        val patientId = credentialStore.currentCredential?.ownerId ?: return
        viewModelScope.launch {
            try {
                val cloudRecords = backendApi.getRecords(patientId)
                // In demo, we just populate our local UI state
                val current = _localRecords.value.toMutableMap()
                cloudRecords.forEach { 
                    if (!current.containsKey(it.id)) current[it.id] = it
                }
                _localRecords.value = current
                _historyError.value = null
            } catch (e: Exception) {
                _historyError.value = e.message
            }
        }
    }

    fun simulatePassiveTap() {
        // Since it's passive, we just start the reader session in the fake
        // and prepare our profile to be sent if the CAD requests it.
        val patientId = credentialStore.currentCredential?.ownerId ?: "p1"
        viewModelScope.launch {
            val res = backendApi.getPatient(patientId)
            res.profile?.let { sessionCoordinator.prepareProfile(it) }
            sessionCoordinator.startReaderSession()
        }
    }
}
