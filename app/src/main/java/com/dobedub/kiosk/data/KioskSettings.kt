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
    /** 문제 발생 시 화면에 띄우는 문의처. 두비덥 대표번호가 기본값이고, 백오피스에서 기기별로 덮어쓸 수 있다. */
    val contactInfo: String = DEFAULT_CONTACT_INFO,
    val kioskLockEnabled: Boolean = true,
    /** 함대 관리 서버 주소. 비어 있으면 BuildConfig.FLEET_SERVER_URL(빌드 기본값)을 사용한다. */
    val fleetServerUrl: String = "",
    /** 기관/도서관 이름 라벨 — 백오피스에서 기기를 식별하기 쉽게. */
    val institutionLabel: String = ""
) {
    val hasPinConfigured: Boolean
        get() = !adminPinHash.isNullOrEmpty() && !adminPinSalt.isNullOrEmpty()
}

/** 기본 관리자 PIN. 납품 시 기기별로 재설정하고 최초 진입 시 변경을 유도한다. */
const val DEFAULT_ADMIN_PIN = "0000"

/** 기본 문의 연락처(두비덥 대표번호). 도서관별로 다르면 백오피스에서 기기마다 덮어쓴다. */
const val DEFAULT_CONTACT_INFO = "02-334-2227"
