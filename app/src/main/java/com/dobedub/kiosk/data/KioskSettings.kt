package com.dobedub.kiosk.data

/** 관리자 설정 화면에서 편집 가능한 키오스크 설정 전체. */
data class KioskSettings(
    val adminPinHash: String? = null,
    val adminPinSalt: String? = null,
    val startUrl: String = "https://splib.dobedub.com/home",
    val allowedDomains: List<String> = listOf("splib.dobedub.com"),
    val idleTimeoutMinutes: Int = 5,
    val autoPlayNext: Boolean = false,
    val volumeMax: Int = 70,
    val brightness: Int = 80,
    val contactInfo: String = "",
    val kioskLockEnabled: Boolean = true
) {
    val hasPinConfigured: Boolean
        get() = !adminPinHash.isNullOrEmpty() && !adminPinSalt.isNullOrEmpty()
}

/** 기본 관리자 PIN. 납품 시 기기별로 재설정하고 최초 진입 시 변경을 유도한다. */
const val DEFAULT_ADMIN_PIN = "0000"
