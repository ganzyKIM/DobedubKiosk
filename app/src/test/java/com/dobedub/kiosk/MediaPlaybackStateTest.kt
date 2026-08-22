package com.dobedub.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaPlaybackStateTest {

    @Before
    fun reset() {
        MediaPlaybackState.videoPlaying = false
        MediaPlaybackState.clearWebMedia()
    }

    @Test
    fun `기본 상태에서는 감상 중이 아니다`() {
        assertFalse(MediaPlaybackState.isWatching(nowMs = 1_000L))
    }

    @Test
    fun `동영상 재생 중이면 감상 중`() {
        MediaPlaybackState.videoPlaying = true
        assertTrue(MediaPlaybackState.isWatching(nowMs = 1_000L))
    }

    @Test
    fun `웹 하트비트는 유지시간 안에서만 감상 중 - 멎으면 스스로 풀린다`() {
        MediaPlaybackState.noteWebMediaHeartbeat(nowMs = 10_000L)
        assertTrue(MediaPlaybackState.isWatching(nowMs = 10_000L + MediaPlaybackState.WEB_HOLD_MS - 1))
        // 페이지 이동·크래시로 하트비트가 소리 없이 끊겨도 lease 가 만료돼 홈 복귀가 살아난다
        assertFalse(MediaPlaybackState.isWatching(nowMs = 10_000L + MediaPlaybackState.WEB_HOLD_MS))
    }

    @Test
    fun `웹뷰 화면을 떠나면 즉시 해제된다`() {
        MediaPlaybackState.noteWebMediaHeartbeat(nowMs = 10_000L)
        MediaPlaybackState.clearWebMedia()
        assertFalse(MediaPlaybackState.isWatching(nowMs = 10_001L))
    }

    @Test
    fun `하트비트를 다시 보내면 유지시간이 연장된다`() {
        MediaPlaybackState.noteWebMediaHeartbeat(nowMs = 10_000L)
        MediaPlaybackState.noteWebMediaHeartbeat(nowMs = 40_000L)
        assertTrue(MediaPlaybackState.isWatching(nowMs = 40_000L + MediaPlaybackState.WEB_HOLD_MS - 1))
    }
}
