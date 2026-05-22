package com.lnsgroup.elise.watch.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
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

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(EliseForegroundService.EXTRA_STATE) ?: return
            val state = try { EliseState.valueOf(stateName) } catch (e: Exception) { EliseState.WAITING }
            binding.particleView.state = state
        }
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startEliseService()
        else Toast.makeText(this, "Microphone required for ELISE", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.particleView.isClickable = true
        binding.particleView.setOnClickListener {
            android.util.Log.d("ELISE_TAP", "tap recu")
            startService(Intent(this, EliseForegroundService::class.java).apply {
                action = EliseForegroundService.ACTION_ACTIVATE
            })
        }

        val filter = IntentFilter(EliseForegroundService.ACTION_STATE_CHANGED)
        registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)

        loadPrefs()
        checkPermissions()
        UpdateChecker.checkAsync(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        if (isFinishing) {
            EliseForegroundService.stop(this)
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startEliseService()
        } else {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startEliseService() {
        EliseForegroundService.start(this)
        binding.particleView.state = EliseState.WAITING
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(Config.PREF_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Config.KEY_SERVER_URL, Config.WS_URL_PROD)
            .apply()
        if (!prefs.contains(Config.KEY_TOKEN)) {
            prefs.edit()
                .putString(Config.KEY_TOKEN, Config.PRELOADED_TOKEN)
                .apply()
        }
    }
}
