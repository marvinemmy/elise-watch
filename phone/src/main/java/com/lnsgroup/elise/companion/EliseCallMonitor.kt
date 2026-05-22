package com.lnsgroup.elise.companion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "EliseCallMonitor"

/**
 * Détecte l'état des appels téléphoniques.
 * En appel actif : bascule l'overlay en mode TEXTE (silence vocal, affiche les infos à l'écran).
 * Fin d'appel : repasse en mode normal.
 */
class EliseCallMonitor : Service() {

    private var tm: TelephonyManager? = null
    private var callListener: Any? = null  // PhoneStateListener ou TelephonyCallback

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        startCallDetection()
        Log.i(TAG, "Call monitor started")
    }

    @Suppress("DEPRECATION")
    private fun startCallDetection() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // API 31+ : TelephonyCallback
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            tm?.registerTelephonyCallback(mainExecutor, cb)
            callListener = cb
        } else {
            // API < 31 : PhoneStateListener
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state)
                }
            }
            tm?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            callListener = listener
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Appel en cours — mode texte uniquement
                Log.i(TAG, "Call active → text-only mode")
                EliseOverlayService.setCallMode(this, true)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // Appel entrant — afficher l'identifiant si connu
                EliseOverlayService.setCallMode(this, true)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Fin d'appel — retour mode normal
                Log.i(TAG, "Call ended → voice mode restored")
                EliseOverlayService.setCallMode(this, false)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (callListener as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
        } else {
            (callListener as? PhoneStateListener)?.let {
                tm?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startService(Intent(ctx, EliseCallMonitor::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, EliseCallMonitor::class.java))
    }
}
