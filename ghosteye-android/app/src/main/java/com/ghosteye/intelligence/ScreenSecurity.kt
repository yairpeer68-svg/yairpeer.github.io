package com.ghosteye.intelligence

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? Activity
}

/** Protect only the exact UI state that contains a credential.
 * Normal Ghost Eye screens remain screenshot/screen-recording friendly.
 */
@Composable
fun SensitiveContentProtection(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity != null && enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (activity != null) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (activity != null) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
