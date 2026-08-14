package com.dobedub.kiosk.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 지금 이 기기가 내려받는 중인 파일들의 진행 상태.
 *
 * AppUpdater 가 다운로드 루프에서 채우고, 동영상 목록 화면이 구독해 "받는 중" 카드를
 * 그린다. 프로세스 로컬 스냅샷이라 영속화하지 않는다 — 앱이 재시작되면 다운로드도 같이
 * 끊기므로 남길 것이 없고, 서버 쪽 진행률(/api/progress)은 별도로 보고된다.
 */
object DownloadState {
    data class Transfer(
        val kind: String,      // "video" | "apk"
        val name: String,
        val received: Long,
        val total: Long        // 모르면 0 (화면에서는 퍼센트 대신 스피너)
    )

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers

    fun update(kind: String, name: String, received: Long, total: Long) {
        _transfers.value = _transfers.value + ("$kind|$name" to Transfer(kind, name, received, total))
    }

    /** 완료·실패 공통 — 카드가 사라진다. 실패 상세는 서버 보고와 로그로 남는다. */
    fun finish(kind: String, name: String) {
        _transfers.value = _transfers.value - "$kind|$name"
    }
}
