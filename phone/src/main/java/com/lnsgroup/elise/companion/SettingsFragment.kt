package com.lnsgroup.elise.companion

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.lnsgroup.elise.companion.databinding.FragmentSettingsBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvVersionPhone.text = "v${BuildConfig.VERSION_NAME}"

        binding.btnCheckUpdate.setOnClickListener {
            binding.tvOtaStatus.text = "Vérification en cours…"
            WatchOtaManager.checkAndPush(requireContext()) { status ->
                binding.tvOtaStatus.text = status
            }
            UpdateChecker.checkNow(requireContext()) { status ->
                if (!status.startsWith("À jour")) binding.tvOtaStatus.text = "$status\n(Companion: $status)"
            }
        }

        binding.btnStopAll.setOnClickListener {
            EliseOverlayService.stop(requireContext())
            EliseCallMonitor.stop(requireContext())
            requireActivity().finish()
        }

        setupVariables()
        loadServerStatus()
        checkOtaStatus()
    }

    private fun loadServerStatus() {
        scope.launch {
            try {
                val (watchV, companionV, routineCount) = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}/api/status")
                        .header("Authorization", "Bearer $TOKEN")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext Triple(0, 0, 0)
                        val j = JSONObject(resp.body!!.string())
                        Triple(j.optInt("watch_version"), j.optInt("companion_version"), j.optInt("routine_count"))
                    }
                }
                binding.tvVersionWatch.text = if (watchV > 0) "v1.0.$watchV (serveur)" else "Inconnu"
                binding.tvVersionServer.text = "✓ Connecté"
                binding.tvRoutineCount.text = "$routineCount"
            } catch (e: Exception) {
                binding.tvVersionServer.text = "Hors ligne"
            }
        }
    }

    private fun setupVariables() {
        val ctx = requireContext()

        // Pre-populate defaults if empty (first launch)
        if (WorkSchedule.getHomeAddress(ctx).isBlank())
            WorkSchedule.setHomeAddress(ctx, "Rowlands Road, Worthing, BN11 3JN")
        if (WorkSchedule.getWorkAddress(ctx).isBlank())
            WorkSchedule.setWorkAddress(ctx, "32 New Ln, Havant, PO9 2NG")

        binding.tvHomeAddr.text = WorkSchedule.getHomeAddress(ctx).ifBlank { "Non configuré" }
        binding.tvWorkAddr.text = WorkSchedule.getWorkAddress(ctx).ifBlank { "Non configuré" }
        binding.switchVacation.isChecked = WorkSchedule.isVacation(ctx)

        binding.btnEditHome.setOnClickListener {
            showEditDialog("Adresse domicile (pour calcul trajet)", WorkSchedule.getHomeAddress(ctx)) { v ->
                WorkSchedule.setHomeAddress(ctx, v)
                binding.tvHomeAddr.text = v
            }
        }
        binding.btnEditWork.setOnClickListener {
            showEditDialog("Adresse de travail", WorkSchedule.getWorkAddress(ctx)) { v ->
                WorkSchedule.setWorkAddress(ctx, v)
                binding.tvWorkAddr.text = v
            }
        }
        binding.switchVacation.setOnCheckedChangeListener { _, checked ->
            WorkSchedule.setVacation(ctx, checked)
        }
        binding.btnReschedule.setOnClickListener {
            RoutineScheduler.scheduleAll(ctx)
            binding.tvOtaStatus.text = "Routines reprogrammees"
        }
        binding.btnTestMorning.setOnClickListener {
            binding.tvOtaStatus.text = "Test routine matin en cours..."
            RoutineExecutor.execute(ctx, RoutineScheduler.ROUTINE_MORNING)
        }
    }

    private fun showEditDialog(label: String, current: String, onSave: (String) -> Unit) {
        val et = EditText(requireContext()).apply {
            setText(current); textSize = 13f; setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(requireContext(), R.style.DialogDark)
            .setTitle(label)
            .setView(et)
            .setPositiveButton("Sauvegarder") { _, _ -> onSave(et.text.toString().trim()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun checkOtaStatus() {
        scope.launch {
            val prefs = requireContext().getSharedPreferences("watch_ota", android.content.Context.MODE_PRIVATE)
            val lastPushed = prefs.getInt("last_pushed_version", 0)
            binding.tvOtaStatus.text = if (lastPushed > 0)
                "Dernière version poussée à la montre : v1.0.$lastPushed\nOuvre l'app pour mettre à jour automatiquement."
            else
                "Première fois — connecte la montre via Bluetooth et lance l'app."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
