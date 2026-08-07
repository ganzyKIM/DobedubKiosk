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

    /** 백오피스 보고용 영상 인벤토리(파일명, 바이트 크기). */
    fun inventory(): List<Pair<String, Long>> =
        (videosDir().listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to it.length() }

    /**
     * 백오피스 지시로 특정 영상 파일을 삭제한다. 경로 조작 방지를 위해 videos 폴더 안의
     * 정확한 파일명만 대상으로 하고, 상위 경로/구분자가 포함된 이름은 거부한다.
     */
    fun deleteVideo(name: String): Boolean {
        if (!isValidName(name)) return false
        val target = File(videosDir(), name)
        return try {
            target.exists() && target.parentFile == videosDir() && target.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 백오피스 지시로 새 영상을 내려받아 저장한다. 최종 파일명과 같은 videos 폴더 안에
     * `.downloading` 임시 파일로 받은 뒤 성공 시 이름을 바꾼다 — 같은 볼륨 안의 rename이라
     * 대용량 파일도 복사 없이 원자적으로 끝나고, 중간에 실패해도 반쪽짜리 파일이
     * 정식 이름으로 남지 않는다.
     */
    fun beginVideoDownload(name: String): File? {
        if (!isValidName(name)) return null
        return File(videosDir(), "$name.downloading")
    }

    fun commitVideoDownload(partial: File, name: String): Boolean {
        if (!isValidName(name)) return false
        return try {
            partial.renameTo(File(videosDir(), name))
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")
}
