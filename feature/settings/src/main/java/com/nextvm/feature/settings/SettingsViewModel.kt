package com.nextvm.feature.settings

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nextvm.core.virtualization.ui.FloatingIconSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.model.EngineStatus
import com.nextvm.core.services.gms.VirtualAccountManager
import com.nextvm.core.services.gms.VirtualGoogleSignInManager
import com.nextvm.core.virtualization.engine.VirtualEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ============================================================
// MVI CONTRACT
// ============================================================

data class GoogleAccountInfo(
    val email: String,
    val displayName: String = "",
    val addedTimeMillis: Long = 0L
)

data class SettingsUiState(
    val engineStatus: String = "Unknown",
    val usedSlots: Int = 0,
    val totalSlots: Int = 10,
    val installedAppCount: Int = 0,
    val fileIsolationEnabled: Boolean = true,
    val permissionFirewallEnabled: Boolean = false,
    val floatingIconEnabled: Boolean = false,
    val verboseLogging: Boolean = false,
    val googleAccounts: List<GoogleAccountInfo> = emptyList()
)

sealed class SettingsIntent {
    data object ToggleFileIsolation : SettingsIntent()
    data object TogglePermissionFirewall : SettingsIntent()
    data object ToggleFloatingIcon : SettingsIntent()
    data object ToggleVerboseLogging : SettingsIntent()
    data object ClearAllData : SettingsIntent()
    data object Refresh : SettingsIntent()
    data object RefreshAccounts : SettingsIntent()
    data object StartAddAccount : SettingsIntent()
    data class RemoveGlobalAccount(val email: String) : SettingsIntent()
}

sealed class SettingsEffect {
    data class ShowMessage(val message: String) : SettingsEffect()
    data class LaunchSignIn(val intent: Intent) : SettingsEffect()
    data class LaunchOverlayPermission(val intent: Intent) : SettingsEffect()
}

// ============================================================
// VIEWMODEL
// ============================================================

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val engine: VirtualEngine,
    private val accountManager: VirtualAccountManager,
    private val signInManager: VirtualGoogleSignInManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    private var pendingSignInId: String? = null
    private var pendingFloatingIconEnable = false

    init {
        loadSettings()
        loadAccounts()
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ToggleFileIsolation -> toggleFileIsolation()
            is SettingsIntent.TogglePermissionFirewall -> togglePermissionFirewall()
            is SettingsIntent.ToggleFloatingIcon -> toggleFloatingIcon()
            is SettingsIntent.ToggleVerboseLogging -> toggleVerboseLogging()
            is SettingsIntent.ClearAllData -> clearAllData()
            is SettingsIntent.Refresh -> loadSettings()
            is SettingsIntent.RefreshAccounts -> loadAccounts()
            is SettingsIntent.StartAddAccount -> startAddAccount()
            is SettingsIntent.RemoveGlobalAccount -> removeGlobalAccount(intent.email)
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val apps = engine.getInstalledApps()
                val status = engine.getEngineStatus()

                if (pendingFloatingIconEnable && Settings.canDrawOverlays(appContext)) {
                    pendingFloatingIconEnable = false
                    FloatingIconSettings.setEnabled(appContext, true)
                }
                val floatingIcon = FloatingIconSettings.isEnabled(appContext) &&
                    Settings.canDrawOverlays(appContext)

                _uiState.update {
                    it.copy(
                        floatingIconEnabled = floatingIcon,
                        engineStatus = when (status) {
                            EngineStatus.READY -> "Running"
                            EngineStatus.INITIALIZING -> "Initializing..."
                            EngineStatus.ERROR -> "Error"
                            EngineStatus.NOT_INITIALIZED -> "Not Started"
                        },
                        installedAppCount = apps.size,
                        usedSlots = apps.count { app -> app.isRunning }
                    )
                }
            } catch (e: Exception) {
                Timber.tag("SettingsVM").e(e, "Failed to load settings")
            }
        }
    }

    private fun toggleFileIsolation() {
        _uiState.update { it.copy(fileIsolationEnabled = !it.fileIsolationEnabled) }
        viewModelScope.launch {
            _effects.emit(
                SettingsEffect.ShowMessage(
                    if (_uiState.value.fileIsolationEnabled) "File isolation enabled"
                    else "File isolation disabled — apps share host filesystem"
                )
            )
        }
    }

    private fun togglePermissionFirewall() {
        _uiState.update { it.copy(permissionFirewallEnabled = !it.permissionFirewallEnabled) }
        viewModelScope.launch {
            _effects.emit(
                SettingsEffect.ShowMessage(
                    if (_uiState.value.permissionFirewallEnabled) "Permission firewall enabled"
                    else "Permission firewall disabled"
                )
            )
        }
    }

    private fun toggleFloatingIcon() {
        viewModelScope.launch {
            val turningOn = !_uiState.value.floatingIconEnabled
            if (!turningOn) {
                pendingFloatingIconEnable = false
                FloatingIconSettings.setEnabled(appContext, false)
                _uiState.update { it.copy(floatingIconEnabled = false) }
                _effects.emit(SettingsEffect.ShowMessage("Floating icon hidden"))
                return@launch
            }
            if (!Settings.canDrawOverlays(appContext)) {
                pendingFloatingIconEnable = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${appContext.packageName}")
                )
                _effects.emit(SettingsEffect.LaunchOverlayPermission(intent))
                _effects.emit(SettingsEffect.ShowMessage("Allow display over other apps, then return here"))
                return@launch
            }
            FloatingIconSettings.setEnabled(appContext, true)
            _uiState.update { it.copy(floatingIconEnabled = true) }
            _effects.emit(SettingsEffect.ShowMessage("Floating icon will show on virtual apps"))
        }
    }

    private fun toggleVerboseLogging() {
        _uiState.update { it.copy(verboseLogging = !it.verboseLogging) }
        Timber.tag("SettingsVM").d("Verbose logging: ${_uiState.value.verboseLogging}")
    }

    private fun clearAllData() {
        viewModelScope.launch {
            try {
                val apps = engine.getInstalledApps()
                apps.forEach { app ->
                    engine.uninstallApp(app.instanceId)
                }
                loadSettings()
                _effects.emit(SettingsEffect.ShowMessage("All virtual app data cleared"))
                Timber.tag("SettingsVM").i("All data cleared")
            } catch (e: Exception) {
                Timber.tag("SettingsVM").e(e, "Failed to clear data")
                _effects.emit(SettingsEffect.ShowMessage("Failed to clear data: ${e.message}"))
            }
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            try {
                accountManager.initialize(appContext)

                val allAccounts = accountManager.getAllAccounts()
                val globalAccounts = allAccounts.filter { it.key.startsWith("global_") }

                _uiState.update { state ->
                    state.copy(
                        googleAccounts = globalAccounts.values.map { acc ->
                            GoogleAccountInfo(
                                email = acc.email,
                                displayName = acc.displayName.ifEmpty { acc.email.substringBefore("@") },
                                addedTimeMillis = acc.createdAt
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                Timber.tag("SettingsVM").e(e, "Failed to load Google accounts")
            }
        }
    }

    private fun startAddAccount() {
        viewModelScope.launch {
            try {
                signInManager.initialize(appContext)
                val instanceId = "global_pending_${System.currentTimeMillis()}"
                pendingSignInId = instanceId
                val intent = signInManager.createSignInIntent(instanceId)
                _effects.emit(SettingsEffect.LaunchSignIn(intent))
            } catch (e: Exception) {
                Timber.tag("SettingsVM").e(e, "Failed to start Google Sign-In")
                _effects.emit(SettingsEffect.ShowMessage("Google Sign-In unavailable"))
            }
        }
    }

    fun handleSignInResult(data: Intent?) {
        val instanceId = pendingSignInId ?: return
        pendingSignInId = null

        viewModelScope.launch {
            val account = signInManager.handleSignInResult(instanceId, data)
            if (account != null) {
                // Store as global account with email-based key
                accountManager.addAccount(
                    instanceId = "global_${account.email.hashCode()}",
                    email = account.email,
                    displayName = account.displayName
                )
                // Clean up the pending state
                accountManager.removeAccount(instanceId)
                loadAccounts()
                _effects.emit(SettingsEffect.ShowMessage("Account added: ${account.email}"))
            } else {
                accountManager.removeAccount(instanceId)
                _effects.emit(SettingsEffect.ShowMessage("Sign-in cancelled"))
            }
        }
    }

    private fun addGlobalAccount(email: String, displayName: String) {
        viewModelScope.launch {
            accountManager.addAccount(
                instanceId = "global_${email.hashCode()}",
                email = email,
                displayName = displayName
            )
            loadAccounts()
            _effects.emit(SettingsEffect.ShowMessage("Account added: $email"))
        }
    }

    private fun removeGlobalAccount(email: String) {
        viewModelScope.launch {
            accountManager.removeAccount("global_${email.hashCode()}")
            loadAccounts()
            _effects.emit(SettingsEffect.ShowMessage("Account removed"))
        }
    }
}
