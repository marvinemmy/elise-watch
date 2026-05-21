package com.lnsgroup.elise.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Gère toutes les permissions Élise en une seule séquence au démarrage.
 * Ordre : overlay (critique) → micro → téléphone → caméra → localisation → contacts/agenda.
 */
class ElisePermissionManager(private val activity: AppCompatActivity) {

    private lateinit var multiPermLauncher: ActivityResultLauncher<Array<String>>
    private var onComplete: (() -> Unit)? = null

    // Permissions standard groupées
    private val CORE_PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }.toTypedArray()

    fun register() {
        multiPermLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                android.util.Log.w("ElisePerm", "Permissions refusées: $denied")
            }
            onComplete?.invoke()
        }
    }

    fun requestAll(onDone: () -> Unit) {
        onComplete = onDone

        // 1. Overlay en premier (permission spéciale)
        if (!Settings.canDrawOverlays(activity)) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"))
            )
        }

        // 2. Toutes les permissions standard
        val needed = CORE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isEmpty()) {
            onDone()
        } else {
            multiPermLauncher.launch(needed)
        }
    }

    fun hasOverlay() = Settings.canDrawOverlays(activity)
    fun hasMic() = activity.checkSelf(Manifest.permission.RECORD_AUDIO)
    fun hasCamera() = activity.checkSelf(Manifest.permission.CAMERA)
    fun hasLocation() = activity.checkSelf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasContacts() = activity.checkSelf(Manifest.permission.READ_CONTACTS)
    fun hasCalendar() = activity.checkSelf(Manifest.permission.READ_CALENDAR)
    fun hasPhone() = activity.checkSelf(Manifest.permission.READ_PHONE_STATE)

    private fun Activity.checkSelf(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}
