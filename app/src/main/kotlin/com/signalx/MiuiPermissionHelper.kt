package com.signalX

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object MiuiPermissionHelper {

    // MIUI ke "Create desktop shortcuts" permission page pe seedha le jaata hai.
    // Agar yeh specific screen support nahi karta (kuch MIUI versions mein),
    // to normal App Info page pe fallback karta hai.

    fun openShortcutPermissionSettings(context: Context) {

        try {

            val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
            intent.setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            intent.putExtra("extra_pkgname", context.packageName)

            context.startActivity(intent)

        } catch (e: ActivityNotFoundException) {

            // Fallback — normal App Info page

            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")

            context.startActivity(intent)
        }
    }
}