package com.dobedub.kiosk.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dobedub.kiosk.admin.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kiosk_settings")

/**
 * 관리자 설정의 단일 진실 공급원(source of truth). DataStore 기반으로 영속화한다.
 */
class KioskSettingsRepository(private val context: Context) {

    private object Keys {
        val ADMIN_PIN_HASH = stringPreferencesKey("admin_pin_hash")
        val ADMIN_PIN_SALT = stringPreferencesKey("admin_pin_salt")
        val START_URL = stringPreferencesKey("start_url")
        val ALLOWED_DOMAINS = stringPreferencesKey("allowed_domains") // comma-separated
        val IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val VOLUME_MAX = intPreferencesKey("volume_max")
        val BRIGHTNESS = intPreferencesKey("brightness")
        val CONTACT_INFO = stringPreferencesKey("contact_info")
        val KIOSK_LOCK_ENABLED = booleanPreferencesKey("kiosk_lock_enabled")
        val FLEET_SERVER_URL = stringPreferencesKey("fleet_server_url")
        val INSTITUTION_LABEL = stringPreferencesKey("institution_label")
    }

    val settingsFlow: Flow<KioskSettings> = context.dataStore.data.map { prefs ->
        val defaults = KioskSettings()
        KioskSettings(
            adminPinHash = prefs[Keys.ADMIN_PIN_HASH],
            adminPinSalt = prefs[Keys.ADMIN_PIN_SALT],
            startUrl = prefs[Keys.START_URL] ?: defaults.startUrl,
            allowedDomains = prefs[Keys.ALLOWED_DOMAINS]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: defaults.allowedDomains,
            idleTimeoutMinutes = prefs[Keys.IDLE_TIMEOUT_MINUTES] ?: defaults.idleTimeoutMinutes,
            autoPlayNext = prefs[Keys.AUTO_PLAY_NEXT] ?: defaults.autoPlayNext,
            volumeMax = prefs[Keys.VOLUME_MAX] ?: defaults.volumeMax,
            brightness = prefs[Keys.BRIGHTNESS] ?: defaults.brightness,
            contactInfo = prefs[Keys.CONTACT_INFO] ?: defaults.contactInfo,
            kioskLockEnabled = prefs[Keys.KIOSK_LOCK_ENABLED] ?: defaults.kioskLockEnabled,
            fleetServerUrl = prefs[Keys.FLEET_SERVER_URL] ?: defaults.fleetServerUrl,
            institutionLabel = prefs[Keys.INSTITUTION_LABEL] ?: defaults.institutionLabel
        )
    }

    suspend fun currentSettings(): KioskSettings = settingsFlow.first()

    suspend fun setAdminPin(newPin: String) {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(newPin, salt)
        context.dataStore.edit { prefs ->
            prefs[Keys.ADMIN_PIN_SALT] = salt
            prefs[Keys.ADMIN_PIN_HASH] = hash
        }
    }

    suspend fun verifyAdminPin(pin: String): Boolean {
        val current = currentSettings()
        val salt = current.adminPinSalt ?: return false
        val hash = current.adminPinHash ?: return false
        return PinHasher.verify(pin, salt, hash)
    }

    suspend fun setStartUrl(url: String) {
        context.dataStore.edit { it[Keys.START_URL] = url }
    }

    suspend fun setAllowedDomains(domains: List<String>) {
        context.dataStore.edit { it[Keys.ALLOWED_DOMAINS] = domains.joinToString(",") }
    }

    suspend fun setIdleTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.IDLE_TIMEOUT_MINUTES] = minutes }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setVolumeMax(volume: Int) {
        context.dataStore.edit { it[Keys.VOLUME_MAX] = volume }
    }

    suspend fun setBrightness(brightness: Int) {
        context.dataStore.edit { it[Keys.BRIGHTNESS] = brightness }
    }

    suspend fun setContactInfo(text: String) {
        context.dataStore.edit { it[Keys.CONTACT_INFO] = text }
    }

    suspend fun setKioskLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KIOSK_LOCK_ENABLED] = enabled }
    }

    suspend fun setFleetServerUrl(url: String) {
        context.dataStore.edit { it[Keys.FLEET_SERVER_URL] = url.trim() }
    }

    suspend fun setInstitutionLabel(label: String) {
        context.dataStore.edit { it[Keys.INSTITUTION_LABEL] = label.trim() }
    }
}
