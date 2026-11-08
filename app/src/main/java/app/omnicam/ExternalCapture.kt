// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam

import android.app.Activity
import android.content.ContentResolver
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
            // Hanya content:// milik app pemanggil. file:// bisa menunjuk ke data pribadi OmniCam sendiri.
            return ExternalRequest(video, out?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT })
        }

        /**
         * Whether OmniCam may write the result to [uri] for another app. Never to OmniCam's own photos
         * or videos or its own providers, and refuse when that cannot be checked. Rules follow
         * GrapheneOS Camera's ForeignOutputUri (MIT).
         */
        fun outputAllowed(ctx: Context, uri: Uri): Boolean {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
            val auth = uri.authority ?: return false
            if (uri.userInfo != null || uri.port != -1) return false   // "0@media" also reaches MediaStore
            val owner = runCatching { ctx.packageManager.resolveContentProvider(auth, 0)?.packageName }.getOrNull()
            if (owner == ctx.packageName) return false
            if (auth != MediaStore.AUTHORITY) return true
            // The caller's own entry is often still pending (hidden): include pending and trashed rows
            val args = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            return runCatching {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), args, null)
                    ?.use { c -> c.moveToFirst() && c.getString(0) != ctx.packageName } ?: false
            }.getOrDefault(false)
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
            if (!ExternalRequest.outputAllowed(ctx, target)) return Activity.RESULT_CANCELED to result
            return try {
                cr.openInputStream(saved)!!.use { input ->
                    val out = runCatching { cr.openOutputStream(target, "wt") }.getOrNull()
                        ?: cr.openOutputStream(target, "w")
                    out!!.use { input.copyTo(it) }
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
            runCatching { cr.loadThumbnail(saved, Size(256, 256), null) }.getOrNull()   // must fit the ~1 MB result limit
                ?.let { result.putExtra("data", it) }
            result.data = saved
        }
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)   // the caller may open the returned file
        return Activity.RESULT_OK to result
    }
}
