package com.dobedub.kiosk.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 동영상 파일의 첫 프레임을 썸네일로 추출한다(백그라운드 스레드에서 실행). */
object VideoThumbnailLoader {
    suspend fun loadFirstFrame(file: File): Bitmap? = withContext(Dispatchers.IO) {
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
