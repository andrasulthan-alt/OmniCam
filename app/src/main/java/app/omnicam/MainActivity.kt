// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — kamera Android gabungan. Kode asli; lihat NOTICE.

package app.omnicam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.omnicam.model.Mode
import app.omnicam.ui.Chip
import app.omnicam.ui.CameraScreen
import app.omnicam.ui.OmniTheme

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    /** Permintaan dari app lain (IMAGE_CAPTURE / VIDEO_CAPTURE); null = penggunaan biasa. */
    private var request by mutableStateOf<ExternalRequest?>(null)
    private var baselineUri: Uri? = null

    private fun handleIntent(i: Intent?) {
        val r = ExternalRequest.from(i)
        request = r
        baselineUri = vm.engine.ui.value.lastUri
        if (r == null) vm.engine.setExternal(false)
        when {
            r != null -> vm.engine.prepareExternal(if (r.video) Mode.VIDEO else Mode.PHOTO)
            i?.action == "android.media.action.VIDEO_CAMERA" -> vm.engine.setMode(Mode.VIDEO)
            i?.action == "android.media.action.STILL_IMAGE_CAMERA" -> vm.engine.setMode(Mode.PHOTO)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light system-bar icons: the camera UI is black in both system light and dark mode
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleIntent(intent)
        setContent {
            OmniTheme {
                val camUi by vm.engine.ui.collectAsState()
                LaunchedEffect(camUi.lastUri, request) {
                    val r = request
                    val u = camUi.lastUri
                    if (r != null && u != null && u != baselineUri) {
                        val (code, data) = withContext(Dispatchers.IO) {
                            ExternalCapture.buildResult(this@MainActivity, u, r)
                        }
                        setResult(code, data)
                        finish()
                    }
                }
                var granted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    granted = ok
                    recordPermissionResult(Manifest.permission.CAMERA, ok)
                }
                // Coming back from system settings with camera access granted: show the camera right away
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                }
                if (granted) {
                    val req = request
                    CameraScreen(
                        vm.engine, vm.prefs,
                        lockedMode = req?.let { if (it.video) Mode.VIDEO else Mode.PHOTO },
                        onInfoShown = { infoShown = it },
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().background(Color(0xFF0B0F14)).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("OmniCam needs camera permission to show the preview.",
                            color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                        Text("Microphone and location are only requested when you turn on the related feature.",
                            color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        // Denied for good ("don't ask again"): Android shows no dialog any more, so this
                        // opens OmniCam's settings page where camera access can be turned on.
                        Chip("Allow camera") {
                            requestOrOpenSettings(Manifest.permission.CAMERA) { launcher.launch(Manifest.permission.CAMERA) }
                        }
                    }
                }
            }
        }
    }

    /** Camera info screen open: volume keys must not take pictures behind it. */
    private var infoShown = false

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP

    /** Volume keys act as shutter only where there is something to shoot (not in QR mode or on the info screen). */
    private fun volumeShutter() =
        vm.prefs.settings.value.volumeShutter && !infoShown && vm.engine.ui.value.mode != Mode.QR &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && volumeShutter()) {
            if (event?.repeatCount == 0) vm.engine.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Consume the matching key-up too, otherwise some ROMs still change the volume or show the panel
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode) && volumeShutter()) true else super.onKeyUp(keyCode, event)
}
