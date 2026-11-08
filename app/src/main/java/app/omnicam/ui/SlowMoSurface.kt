// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.ui

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.omnicam.camera.CameraEngine

/**
 * Viewfinder for the Camera2 slow-motion recorder. Constrained high-speed sessions need a preview
 * surface of exactly the stream size, so the SurfaceView buffer is fixed to [size]; the camera
 * framework rotates the landscape buffer to match the portrait screen.
 *
 * The view keeps the stream's own aspect ratio and fills the viewfinder like the other modes (the
 * overflow is cut off by the parent's clip), instead of stretching the picture to the box.
 */
@Composable
fun SlowMoSurface(size: Size, engine: CameraEngine, modifier: Modifier = Modifier) {
    // Portrait aspect of the rotated stream, e.g. 720/1280
    val content = minOf(size.width, size.height).toFloat() / maxOf(size.width, size.height)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxRatio = if (maxHeight.value > 0f) maxWidth / maxHeight else content
        val w = if (boxRatio > content) maxWidth else maxHeight * content
        val h = if (boxRatio > content) maxWidth / content else maxHeight
        key(size) {
            AndroidView(
                modifier = Modifier.requiredSize(w, h),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        // Fixed before the surface exists, so a full-screen buffer is never allocated first
                        holder.setFixedSize(size.width, size.height)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {}
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                                if (w == size.width && hgt == size.height) engine.onSlowMoSurface(h.surface, w, hgt)
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                engine.onSlowMoSurface(null)
                            }
                        })
                    }
                },
            )
        }
    }
}
