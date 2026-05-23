package com.lnsgroup.elise.watch.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lnsgroup.elise.watch.Config
import com.lnsgroup.elise.watch.databinding.ActivityMainBinding
import com.lnsgroup.elise.watch.network.UpdateChecker
import com.lnsgroup.elise.watch.service.EliseForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Double tap pour activer Élise
    private lateinit var gestureDetector: GestureDetector

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == EliseForegroundService.ACTION_STATE_CHANGED) {
                val stateName = intent.getStringExtra(EliseForegroundService.EXTRA_STATE) ?: return
                val state = try { EliseState.valueOf(stateName) } catch (_: Exception) { EliseState.WAITING }
                binding.particleView.state = state
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) startEliseService()
        else Toast.makeText(this, "Microphone required for ELISE", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Double tap → activer Élise
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                android.util.Log.d("ELISE_TAP", "double tap recu")
                startService(Intent(this@MainActivity, EliseForegroundService::class.java).apply {
                    action = EliseForegroundService.ACTION_ACTIVATE
                })
                return true
            }
            // Single tap aussi supporté pour compatibilité
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                startService(Intent(this@MainActivity, EliseForegroundService::class.java).apply {
                    action = EliseForegroundService.ACTION_ACTIVATE
                })
                return true
            }
        })

        binding.particleView.isClickable = true
        binding.particleView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val filter = IntentFilter(EliseForegroundService.ACTION_STATE_CHANGED)
        registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)

        loadPrefs()
        checkPermissions()
        requestBatteryOptimizationIgnore()
        UpdateChecker.checkAsync(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        if (isFinishing) EliseForegroundService.stop(this)
    }

    // Demande à l'OS d'ignorer les optimisations batterie pour le service
    private fun requestBatteryOptimizationIgnore() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }
    }

    private fun checkPermissions() {
        val required = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BODY_SENSORS,
            "android.permission.ACTIVITY_RECOGNITION",
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startEliseService()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun startEliseService() {
        EliseForegroundService.start(this)
        binding.particleView.state = EliseState.WAITING
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(Config.PREF_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(Config.KEY_SERVER_URL, Config.WS_URL_PROD).apply()
        if (!prefs.contains(Config.KEY_TOKEN)) {
            prefs.edit().putString(Config.KEY_TOKEN, Config.PRELOADED_TOKEN).apply()
        }
    }
}
