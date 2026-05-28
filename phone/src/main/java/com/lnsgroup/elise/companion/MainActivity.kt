package com.lnsgroup.elise.companion

import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lnsgroup.elise.companion.databinding.ActivityMainBinding

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permManager: ElisePermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation bottom
        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_voice     -> VoiceFragment()
                R.id.nav_routines  -> RoutinesFragment()
                R.id.nav_history   -> HistoryFragment()
                R.id.nav_settings  -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
        }

        // Démarrer sur l'onglet voix
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_voice
        }

        // Planifier les routines quotidiennes
        RoutineScheduler.scheduleAll(this)

        // Permissions
        permManager = ElisePermissionManager(this)
        permManager.register()
        permManager.requestAll { startEliseServices() }
    }

    private fun startEliseServices() {
        if (Settings.canDrawOverlays(this)) EliseOverlayService.start(this)
        if (permManager.hasPhone()) EliseCallMonitor.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) EliseOverlayService.start(this)
    }
}
