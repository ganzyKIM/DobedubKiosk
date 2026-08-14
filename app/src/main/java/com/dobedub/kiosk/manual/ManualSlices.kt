package com.dobedub.kiosk.manual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * 이용안내 이미지 한 장을 화면에 그릴 "조각"들로 나눈 결과.
 *
 * 관리자가 한 장짜리 아주 긴 이미지를 올릴 수 있는데, 그걸 통째로 디코딩하면 비트맵이
 * 수십 MB가 되어 터진다. 그래서 세로로 잘라 보이는 조각만 그린다 — 화면에서는 여전히
 * 하나의 이어진 이미지로 보인다.
 */
data class ManualSlice(
    val file: File,
    /** 원본 이미지 안에서 이 조각이 차지하는 영역. */
    val region: Rect,
    /** 이 조각의 가로/세로 비. 레이아웃 높이를 먼저 확정해 스크롤이 튀지 않게 한다. */
    val aspectRatio: Float
) {
    /** LazyColumn 항목 키. 파일이 바뀌면 다시 그리도록 수정시각을 섞는다. */
    val key: String get() = "${file.name}:${file.lastModified()}:${region.top}"
}

object ManualSlices {

    /** 파일 목록을 화면에 그릴 조각 목록으로 편다. 크기를 못 읽는 파일은 조용히 건너뛴다. */
    fun build(files: List<File>): List<ManualSlice> = files.flatMap { file ->
        val (w, h) = ManualRepository.readSize(file) ?: return@flatMap emptyList()
        val sliceCount = max(1, ceil(h.toDouble() / ManualRepository.MAX_SLICE_PX).toInt())
        val sliceH = ceil(h.toDouble() / sliceCount).toInt()
        (0 until sliceCount).map { i ->
            val top = i * sliceH
            val bottom = minOf(h, top + sliceH)
            ManualSlice(
                file = file,
                region = Rect(0, top, w, bottom),
                aspectRatio = w.toFloat() / (bottom - top).toFloat()
            )
        }
    }

    /**
     * 조각 하나를 [targetWidthPx] 폭에 맞춰 디코딩한다.
     * 원본이 그보다 크면 2의 거듭제곱으로 축소해서 읽는다(메모리 절약).
     */
    suspend fun decode(slice: ManualSlice, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // API 31에서 File 오버로드가 생겼지만 이 경로는 minSdk(29) 부터 모든 버전에서
            // 동작한다 — 분기해서 얻는 게 없으므로 하나로 둔다.
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(slice.file.absolutePath, false)

            try {
                var sample = 1
                while (targetWidthPx > 0 && slice.region.width() / (sample * 2) >= targetWidthPx) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565  // 안내 이미지엔 알파가 필요 없다 — 메모리 절반
                }
                decoder.decodeRegion(slice.region, opts)
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
    }
}
