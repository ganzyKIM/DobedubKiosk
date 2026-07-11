package com.dobedub.kiosk.video

import android.content.Context
import java.io.File

data class VideoItem(
    val title: String,
    val file: File
)

private val SUPPORTED_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm")

/**
 * 앱 전용 폴더(`Android/data/com.dobedub.kiosk/files/videos/`)를 스캔해 재생 가능한 동영상 목록을 만든다.
 * 콘텐츠는 관리자가 USB/adb로 투입하며(§4.2), 파일명이 곧 목록 제목이 된다.
 */
class VideoRepository(private val context: Context) {

    fun videosDir(): File {
        val dir = context.getExternalFilesDir("videos") ?: File(context.filesDir, "videos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listVideos(): List<VideoItem> {
        val dir = videosDir()
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?: emptyArray()
        return files
            .sortedBy { it.name }
            .map { VideoItem(title = it.nameWithoutExtension, file = it) }
    }
}
