package com.lnsgroup.elise.companion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lnsgroup.elise.companion.databinding.FragmentRoutinesBinding
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val DAYS = arrayOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")

data class Routine(val id: String, val description: String, val confirmed: Boolean,
                   val hourSlot: Int, val dayOfWeek: Int, val occurrences: Int)

class RoutinesFragment : Fragment() {

    private var _binding: FragmentRoutinesBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRoutinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvRoutines.layoutManager = LinearLayoutManager(requireContext())
        binding.btnAnalyze.setOnClickListener { loadRoutines() }
        loadRoutines()
    }

    private fun loadRoutines() {
        binding.tvRoutinesEmpty.text = "Chargement…"
        binding.tvRoutinesEmpty.visibility = View.VISIBLE
        scope.launch {
            val routines = withContext(Dispatchers.IO) { fetchRoutines() }
            if (routines.isEmpty()) {
                binding.tvRoutinesEmpty.text =
                    "Aucune routine détectée.\nParle à Élise régulièrement pour qu'elle apprenne tes habitudes."
            } else {
                binding.tvRoutinesEmpty.visibility = View.GONE
                binding.rvRoutines.adapter = RoutineAdapter(routines) { id, confirmed ->
                    patchRoutine(id, confirmed)
                }
            }
        }
    }

    private fun fetchRoutines(): List<Routine> = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/routines")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(resp.body!!.string()).getJSONArray("routines")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Routine(o.getString("id"), o.getString("description"),
                    o.getBoolean("confirmed"), o.getInt("hour_slot"),
                    o.getInt("day_of_week"), o.getInt("occurrences"))
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun patchRoutine(id: String, confirmed: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = """{"confirmed":$confirmed}""".toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/routines/$id")
                    .header("Authorization", "Bearer $TOKEN")
                    .patch(body).build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}

class RoutineAdapter(
    private val items: List<Routine>,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<RoutineAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val desc: TextView = view.findViewById(R.id.tvRoutineDesc)
        val meta: TextView = view.findViewById(R.id.tvRoutineMeta)
        val switch: Switch = view.findViewById(R.id.switchRoutine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_routine, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val r = items[pos]
        vh.desc.text = r.description
        val day = if (r.dayOfWeek in 0..6) DAYS[r.dayOfWeek] else "Tous les jours"
        val h = r.hourSlot * 2
        vh.meta.text = "$day · ${h}h–${h + 2}h · ${r.occurrences}× détecté"
        vh.switch.isChecked = r.confirmed
        vh.switch.setOnCheckedChangeListener { _, checked ->
            onToggle(r.id, checked)
        }
    }
}
