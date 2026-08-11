package com.dobedub.kiosk.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dobedub.kiosk.data.DEFAULT_ADMIN_PIN
import com.dobedub.kiosk.data.KioskSettings
import com.dobedub.kiosk.data.KioskSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
private const val BASE_LOCKOUT_SECONDS = 30

class AdminViewModel(private val repository: KioskSettingsRepository) : ViewModel() {

    private val _settings = MutableStateFlow(KioskSettings())
    val settings: StateFlow<KioskSettings> = _settings.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private var failedAttempts = 0
    private var lockedUntilMillis = 0L

    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { _settings.value = it }
        }
    }

    /** PIN이 아직 설정되지 않은 최초 상태(공장 출고 기본값 사용 중)인지 여부. */
    val isUsingDefaultPin: Boolean
        get() = !_settings.value.hasPinConfigured

    fun submitPin(pin: String, onSuccess: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now < lockedUntilMillis) {
            val remaining = (lockedUntilMillis - now) / 1000 + 1
            _pinError.value = "너무 많이 틀렸어요. ${remaining}초 후 다시 시도하세요."
            return
        }

        viewModelScope.launch {
            val ok = if (isUsingDefaultPin) {
                pin == DEFAULT_ADMIN_PIN
            } else {
                repository.verifyAdminPin(pin)
            }

            if (ok) {
                failedAttempts = 0
                _pinError.value = null
                onSuccess()
            } else {
                failedAttempts++
                if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                    val extra = failedAttempts - MAX_ATTEMPTS_BEFORE_LOCKOUT
                    val lockSeconds = BASE_LOCKOUT_SECONDS * (extra + 1)
                    lockedUntilMillis = System.currentTimeMillis() + lockSeconds * 1000L
                    _pinError.value = "너무 많이 틀렸어요. ${lockSeconds}초 후 다시 시도하세요."
                } else {
                    _pinError.value = "PIN이 올바르지 않습니다."
                }
            }
        }
    }

    fun clearPinError() {
        _pinError.value = null
    }

    fun changePin(newPin: String) = viewModelScope.launch {
        repository.setAdminPin(newPin)
    }

    fun updateStartUrl(url: String) = viewModelScope.launch {
        repository.setStartUrl(url)
    }

    fun updateAllowedDomains(domains: List<String>) = viewModelScope.launch {
        repository.setAllowedDomains(domains)
    }

    fun updateIdleTimeoutMinutes(minutes: Int) = viewModelScope.launch {
        repository.setIdleTimeoutMinutes(minutes)
    }

    fun updateAutoPlayNext(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoPlayNext(enabled)
    }

    fun updateVolumeMax(volume: Int) = viewModelScope.launch {
        repository.setVolumeMax(volume)
    }

    fun updateBrightness(brightness: Int) = viewModelScope.launch {
        repository.setBrightness(brightness)
    }

    fun updateFleetServerUrl(url: String) = viewModelScope.launch {
        repository.setFleetServerUrl(url)
    }

    fun updateInstitutionLabel(label: String) = viewModelScope.launch {
        repository.setInstitutionLabel(label)
    }

    class Factory(private val repository: KioskSettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AdminViewModel(repository) as T
        }
    }
}
