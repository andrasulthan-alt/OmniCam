// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.IntentCompat

/**
 * Mendukung ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE agar OmniCam bisa dipakai app lain
 * (chat, dokumen, form) sebagai kamera sekali-pakai.
 *
 *  - Ada EXTRA_OUTPUT : hasil disalin ke URI itu, lalu salinan di DCIM/OmniCam dihapus.
 *  - Foto tanpa output: thumbnail dikembalikan di extra "data" (file asli tetap tersimpan di galeri).
 *  - Video tanpa output: URI video dikembalikan sebagai data.
 */
class ExternalRequest(val video: Boolean, val output: Uri?) {
    companion object {
        fun from(intent: Intent?): ExternalRequest? {
            if (intent == null) return null
            val video = when (intent.action) {
                MediaStore.ACTION_IMAGE_CAPTURE -> false
                MediaStore.ACTION_VIDEO_CAPTURE -> true
                else -> return null
            }
            val out = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            return ExternalRequest(video, out)
        }
    }
}

object ExternalCapture {
    /** Jalankan di thread IO. Mengembalikan pasangan (resultCode, intent hasil). */
    fun buildResult(ctx: Context, saved: Uri, r: ExternalRequest): Pair<Int, Intent> {
        val cr = ctx.contentResolver
        val result = Intent()
        val target = r.output
        if (target != null) {
            return try {
                cr.openInputStream(saved)!!.use { input ->
                    cr.openOutputStream(target, "wt")!!.use { out -> input.copyTo(out) }
                }
                runCatching { cr.delete(saved, null, null) }
                result.data = target
                Activity.RESULT_OK to result
            } catch (e: Exception) {
                Activity.RESULT_CANCELED to result
            }
        }
        if (r.video) {
            result.data = saved
        } else {
            runCatching { cr.loadThumbnail(saved, Size(640, 640), null) }.getOrNull()
                ?.let { result.putExtra("data", it) }
            result.data = saved
        }
        return Activity.RESULT_OK to result
    }
}
