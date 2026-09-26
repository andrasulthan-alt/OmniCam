// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Temporarily forces the window backlight to maximum. Used as a substitute flash/torch on cameras
 * with no physical flash unit (typically the front camera): a bright screen lights the subject.
 * The previous brightness is restored automatically when [active] turns off or this leaves
 * composition, so it never permanently changes the device's brightness setting.
 */
@Composable
fun ScreenBrightnessBoost(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        val window = (view.context as? Activity)?.window
        val original = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (active && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (active && window != null) {
                window.attributes = window.attributes.apply { screenBrightness = original }
            }
        }
    }
}
