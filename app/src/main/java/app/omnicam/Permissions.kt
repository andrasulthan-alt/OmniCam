// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Asks for [perm] through [launch], or, when Android no longer shows the dialog ("don't ask again"),
 * opens OmniCam's page in system Settings so the user can still turn it on. "Denied for good" is taken
 * from the last dialog result (see [recordPermissionResult]), so an expired one-time grant or an
 * auto-reset permission still gets the normal dialog.
 */
fun Activity.requestOrOpenSettings(perm: String, launch: () -> Unit) {
    val sp = getSharedPreferences("omnicam_permissions", Context.MODE_PRIVATE)
    val key = "denied_$perm"
    if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
        sp.edit().remove(key).apply()
        launch(); return
    }
    if (sp.getBoolean(key, false) && !shouldShowRequestPermissionRationale(perm)) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    } else {
        launch()
    }
}

/** Call from the permission dialog's result: remembers whether Android will still show the dialog. */
fun Activity.recordPermissionResult(perm: String, granted: Boolean) {
    val deniedForGood = !granted && !shouldShowRequestPermissionRationale(perm)
    getSharedPreferences("omnicam_permissions", Context.MODE_PRIVATE).edit()
        .putBoolean("denied_$perm", deniedForGood).apply()
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
