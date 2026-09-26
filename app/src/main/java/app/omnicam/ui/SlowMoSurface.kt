// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.ui

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.omnicam.camera.CameraEngine

/**
 * Viewfinder for the Camera2 slow-motion recorder. Constrained high-speed sessions need a preview
 * surface of exactly the stream size, so the SurfaceView buffer is fixed to [size]; the camera
 * framework rotates the landscape buffer to match the portrait screen.
 */
@Composable
fun SlowMoSurface(size: Size, engine: CameraEngine, modifier: Modifier = Modifier) {
    key(size) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            h.setFixedSize(size.width, size.height)
                        }
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
