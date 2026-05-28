package com.lnsgroup.elise.companion

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lnsgroup.elise.companion.databinding.FragmentDashboardBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── Modèles ─────────────────────────────────────────────────────────────────

data class NodeInfo(
    val country: String,
    val city: String,
    val gpuModel: String,
    val cpuModel: String,
    val creditsEarned: Double,
    val status: String,
)

// ─── Fragment ─────────────────────────────────────────────────────────────────

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvContributors.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContributors.isNestedScrollingEnabled = false

        binding.btnRefresh.setOnClickListener { loadAll() }

        loadAll()
        startPolling()
    }

    // ─── Chargement ──────────────────────────────────────────────────────────

    private fun loadAll() {
        scope.launch {
            val metrics = withContext(Dispatchers.IO) { fetchMetrics() }
            val revenue = withContext(Dispatchers.IO) { fetchRevenue() }
            val nodes   = withContext(Dispatchers.IO) { fetchNodes() }
            applyMetrics(metrics)
            applyRevenue(revenue)
            applyNodes(nodes)
        }
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (true) {
                delay(5_000)
                loadAll()
            }
        }
    }

    // ─── API ─────────────────────────────────────────────────────────────────

    private fun fetchMetrics(): JSONObject? = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/admin/metrics")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) JSONObject(resp.body!!.string()) else null
        }
    } catch (_: Exception) { null }

    private fun fetchRevenue(): JSONObject? = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/admin/revenue")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) JSONObject(resp.body!!.string()) else null
        }
    } catch (_: Exception) { null }

    private fun fetchNodes(): List<NodeInfo> = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/nodes/active")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr: JSONArray = when (val body = JSONObject("{\"d\":${resp.body!!.string()}}").get("d")) {
                is JSONArray -> body
                else -> return emptyList()
            }
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NodeInfo(
                    country       = o.optString("country", "??"),
                    city          = o.optString("city", ""),
                    gpuModel      = o.optString("gpu_model", ""),
                    cpuModel      = o.optString("cpu_model", "CPU"),
                    creditsEarned = o.optDouble("credits_earned", 0.0),
                    status        = o.optString("status", "offline"),
                )
            }.sortedByDescending { it.creditsEarned }.take(8)
        }
    } catch (_: Exception) { emptyList() }

    // ─── Affichage ───────────────────────────────────────────────────────────

    private fun applyMetrics(m: JSONObject?) {
        if (m == null) { showError(); return }
        binding.tvNodes.text    = m.optInt("nodes_online", 0).toString()
        binding.tvNodesSub.text = "/ ${m.optInt("nodes_total", 0)} enregistrés"
        binding.tvTasks.text    = m.optInt("tasks_completed", 0).toString()
        binding.tvStatus.text   = "OPÉRATIONNEL"
        binding.tvStatus.setTextColor(Color.parseColor("#00FF88"))
        binding.statusDot.setBackgroundColor(Color.parseColor("#00FF88"))
    }

    private fun applyRevenue(r: JSONObject?) {
        if (r == null) return
        val total = r.optDouble("total_revenue", 0.0)
        val count = r.optInt("transaction_count", 0)
        binding.tvRevenue.text    = NumberFormat.getCurrencyInstance(Locale.FRANCE).format(total)
        binding.tvRevenueSub.text = "$count transactions"
    }

    private fun applyNodes(nodes: List<NodeInfo>) {
        if (nodes.isEmpty()) {
            binding.tvContribEmpty.visibility = View.VISIBLE
            binding.tvContribEmpty.text = "Aucun nœud actif"
            return
        }
        binding.tvContribEmpty.visibility = View.GONE
        val maxCredits = nodes.first().creditsEarned.coerceAtLeast(1.0)
        binding.rvContributors.adapter = ContributorAdapter(nodes, maxCredits)
    }

    private fun showError() {
        binding.tvStatus.text = "HORS LIGNE"
        binding.tvStatus.setTextColor(Color.parseColor("#FF2255"))
        binding.statusDot.setBackgroundColor(Color.parseColor("#FF2255"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
        scope.cancel()
        _binding = null
    }
}

// ─── Adapter contributeurs ────────────────────────────────────────────────────

class ContributorAdapter(
    private val items: List<NodeInfo>,
    private val maxCredits: Double,
) : RecyclerView.Adapter<ContributorAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView    = view.findViewById(R.id.tvRank)
        val name: TextView    = view.findViewById(R.id.tvNodeName)
        val detail: TextView  = view.findViewById(R.id.tvNodeDetail)
        val bar: View         = view.findViewById(R.id.progressBar)
        val dot: View         = view.findViewById(R.id.statusDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_contributor, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val node = items[pos]
        vh.rank.text   = (pos + 1).toString()
        vh.name.text   = "${node.country} — ${node.gpuModel.ifBlank { node.cpuModel }}"
        vh.detail.text = "${node.creditsEarned.toLong()} crédits · ${node.city}"

        // Largeur barre proportionnelle
        val fraction = (node.creditsEarned / maxCredits).toFloat().coerceIn(0f, 1f)
        val parent   = (vh.bar.parent as FrameLayout)
        parent.post {
            val params = vh.bar.layoutParams
            params.width = (parent.width * fraction).toInt()
            vh.bar.layoutParams = params
        }

        // Couleur statut
        val dotColor = if (node.status == "online") "#00FF88" else "#335566"
        vh.dot.setBackgroundColor(Color.parseColor(dotColor))
    }
}
