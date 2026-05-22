package com.lnsgroup.elise.companion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lnsgroup.elise.companion.databinding.FragmentHistoryBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HistoryEntry(val user: String, val elise: String, val time: String)

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true; stackFromEnd = true
        }
        loadHistory()
    }

    private fun loadHistory() {
        binding.tvHistoryEmpty.text = "Chargement…"
        binding.tvHistoryEmpty.visibility = View.VISIBLE
        scope.launch {
            val entries = withContext(Dispatchers.IO) { fetchHistory() }
            if (entries.isEmpty()) {
                binding.tvHistoryEmpty.text = "Aucun historique disponible."
            } else {
                binding.tvHistoryEmpty.visibility = View.GONE
                binding.rvHistory.adapter = HistoryAdapter(entries)
            }
        }
    }

    private fun fetchHistory(): List<HistoryEntry> = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/history?limit=30")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(resp.body!!.string()).getJSONArray("history")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.optString("user_message", o.optString("transcript", "…")),
                    o.optString("elise_response", o.optString("response", "…")),
                    o.optString("timestamp", "").take(16).replace("T", " ")
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}

class HistoryAdapter(private val items: List<HistoryEntry>) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val user: TextView = view.findViewById(R.id.tvHistoryUser)
        val elise: TextView = view.findViewById(R.id.tvHistoryElise)
        val time: TextView = view.findViewById(R.id.tvHistoryTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val e = items[pos]
        vh.user.text = e.user
        vh.elise.text = e.elise
        vh.time.text = e.time
    }
}
