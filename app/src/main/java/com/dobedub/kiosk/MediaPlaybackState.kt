package com.dobedub.kiosk

/**
 * 감상 중 여부의 단일 출처. 무조작 홈 복귀가 이 값을 보고 감상 중에는 복귀를 미룬다.
 *
 *  - 동영상: ExoPlayer isPlaying 을 그대로 쓴다. 화면을 떠나면 해제된다.
 *  - 웹뷰: 주입 JS 의 재생 감지 하트비트를 만료가 있는 lease 로 받는다. 페이지 이동이나
 *    크래시로 신호가 끊겨도 WEB_HOLD_MS 뒤 저절로 풀린다.
 */
object MediaPlaybackState {

    /** 웹 하트비트 1회가 감상 상태를 유지시키는 시간. JS 발신 주기(15초)의 3배 여유. */
    const val WEB_HOLD_MS = 45_000L

    /** 동영상 플레이어가 실제 재생 중인가 (ExoPlayer onIsPlayingChanged 가 직접 쓴다). */
    @Volatile
    var videoPlaying: Boolean = false

    @Volatile
    private var webMediaUntilMs: Long = 0L

    /** 웹뷰 페이지가 "지금 미디어 재생 중" 하트비트를 보냈다. */
    fun noteWebMediaHeartbeat(nowMs: Long = System.currentTimeMillis()) {
        webMediaUntilMs = nowMs + WEB_HOLD_MS
    }

    /** 웹뷰 화면을 떠날 때 즉시 해제 — lease 만료(45초)를 기다릴 필요가 없다. */
    fun clearWebMedia() {
        webMediaUntilMs = 0L
    }

    fun isWatching(nowMs: Long = System.currentTimeMillis()): Boolean =
        videoPlaying || nowMs < webMediaUntilMs
}
