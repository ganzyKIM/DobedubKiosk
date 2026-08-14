package com.dobedub.kiosk.manual

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/**
 * 홈 화면 이용안내 이미지를 백오피스가 지정한 세트로 유지한다.
 *
 * 파일명 앞에 순서 번호가 붙어 오므로(`000_…`, `001_…`) **파일명을 정렬하면 곧 표시 순서**다.
 * 순서를 따로 저장할 필요가 없고, 폴더만 보면 상태를 알 수 있다.
 *
 * 세트가 비면(관리자가 전부 비웠거나 한 번도 지정 안 함) 앱 내장 이미지를 쓴다.
 */
class ManualRepository(private val context: Context) {

    fun dir(): File {
        val d = context.getExternalFilesDir("manual") ?: File(context.filesDir, "manual")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 표시 순서대로 정렬된 현재 이미지 목록. 비어 있으면 내장 이미지를 쓰라는 뜻. */
    fun list(): List<File> =
        (dir().listFiles { f -> f.isFile && !f.name.endsWith(TEMP_SUFFIX) } ?: emptyArray())
            .sortedBy { it.name }

    /**
     * 백오피스가 준 목록과 폴더를 똑같이 맞춘다.
     *
     * @param wanted 파일명 → 기대 크기(바이트). 서버가 준 순서대로의 이름이 그대로 들어온다.
     * @param download 아직 없는 파일을 받아오는 함수(파일명 → 임시파일로 저장). 실패 시 예외.
     * @return 실제로 변경이 있었으면 true(호출부가 화면 갱신 여부를 판단한다).
     */
    fun sync(wanted: Map<String, Long>, download: (name: String, dest: File) -> Unit): Boolean {
        var changed = false
        val existing = list().associateBy { it.name }

        // 1) 세트에서 빠진 파일 제거. 관리자가 낱장을 지웠거나 전체를 비운 경우.
        for ((name, file) in existing) {
            if (!wanted.containsKey(name)) {
                if (file.delete()) changed = true
            }
        }

        // 2) 없는 파일 확보. 순서만 바뀐 경우는 내용 해시가 이름에 들어 있어 같은 파일이
        //    다른 번호로 이미 있다 — 다시 받지 않고 이름만 바꾼다(대역폭 낭비 방지).
        val remaining = list().associateBy { it.name }
        for ((name, size) in wanted) {
            val current = remaining[name]
            if (current != null && current.length() == size) continue

            val reusable = remaining.values.firstOrNull {
                it.name != name && contentKey(it.name) == contentKey(name) && it.length() == size
            }
            val dest = File(dir(), name)
            if (reusable != null) {
                if (reusable.renameTo(dest)) { changed = true; continue }
            }
            val tmp = File(dir(), "$name$TEMP_SUFFIX")
            if (tmp.exists()) tmp.delete()
            download(name, tmp)
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) changed = true else tmp.delete()
        }
        return changed
    }

    /** `000_a1b2c3d4e5f6.png` → `a1b2c3d4e5f6.png` (순서 번호를 뗀 내용 식별자). */
    private fun contentKey(name: String): String = name.substringAfter('_', name)

    companion object {
        private const val TEMP_SUFFIX = ".downloading"

        /**
         * 한 조각의 최대 원본 높이(px). 이보다 긴 이미지는 이 높이 단위로 잘라서 그린다.
         *
         * 이용안내는 "한 장짜리 긴 이미지"일 수 있는데, 그런 이미지를 통째로 디코딩하면
         * 비트맵이 수십 MB가 되어 OOM 이 나거나 GPU 텍스처 한도를 넘어 아예 안 그려진다.
         * (앱 내장 이용안내를 12장으로 미리 쪼개둔 것도 같은 이유다.)
         * 조각 단위로 그리면 화면에 보이는 부분만 메모리에 올라간다.
         */
        const val MAX_SLICE_PX = 2048

        /** 파일을 디코딩하지 않고 크기만 읽는다(헤더만 훑으므로 저렴하다). */
        fun readSize(file: File): Pair<Int, Int>? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            return runCatching {
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            }.getOrNull()
        }
    }
}
