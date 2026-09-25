// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.omnicam.camera.CameraEngine
import app.omnicam.storage.Prefs

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val engine = CameraEngine(app, prefs)

    override fun onCleared() {
        engine.release()
    }
}
