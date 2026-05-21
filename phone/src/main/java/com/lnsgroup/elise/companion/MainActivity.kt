package com.lnsgroup.elise.companion

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lnsgroup.elise.companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        binding.btnUpdate.setOnClickListener {
            binding.btnUpdate.isEnabled = false
            binding.tvStatus.text = "Vérification en cours…"
            UpdateChecker.checkNow(this) { result ->
                runOnUiThread {
                    binding.btnUpdate.isEnabled = true
                    binding.tvStatus.text = result
                }
            }
        }

        UpdateChecker.checkAsync(this) { status ->
            runOnUiThread { binding.tvStatus.text = status }
        }
    }
}
