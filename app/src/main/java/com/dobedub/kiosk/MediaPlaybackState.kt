package com.dobedub.kiosk

/**
 * "아이가 지금 감상 중인가"의 단일 출처. 무조작 홈 복귀(MainActivity.returnToHomeIfIdle)가
 * 이 값을 보고 감상 중에는 복귀를 미룬다 — 무조작 판정이 터치만 봐서, 5분보다 긴 영상을
 * 얌전히 보던 아이가 재생 도중 홈으로 튕기는 문제(서비스_완성도_검토.md §1-1)의 수정.
 *
 * 두 출처는 신뢰도가 달라 취급도 다르다:
 *  - 동영상(ExoPlayer): isPlaying 콜백이 정확하고 화면 dispose 에서 반드시 해제된다 → boolean.
 *  - 웹뷰(더빙/웹툰 오디오): 페이지에 주입한 JS 가 재생을 감지해 하트비트를 보낸다.
 *    페이지 이동·크래시로 신호가 소리 없이 끊길 수 있어서, boolean 로 두면 "영영 홈으로
 *    안 돌아가는 기기"가 나올 수 있다 → **만료가 있는 임차(lease)** 로 잡는다: 하트비트가
 *    멎으면 WEB_HOLD_MS 뒤 저절로 풀린다. 게이트가 아니라 lease 인 이유가 이 자기복구다.
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
