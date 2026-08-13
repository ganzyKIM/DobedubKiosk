package com.dobedub.kiosk.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 동영상 목록 썸네일. 백오피스에서 등록한 커스텀 이미지(`<파일명>.jpg`)가 있으면 그걸 쓰고,
 * 없으면 지금까지처럼 영상 첫 프레임을 추출한다(백그라운드 스레드에서 실행).
 */
object VideoThumbnailLoader {
    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        // 확장자는 .jpg 로 고정이지만 내용물은 png/webp 여도 된다 — BitmapFactory 는 내용을 보고 디코딩한다.
        val custom = File(file.parentFile, "${file.name}.jpg")
        if (custom.exists()) {
            runCatching { BitmapFactory.decodeFile(custom.absolutePath) }.getOrNull()?.let { return@withContext it }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
