package com.lnsgroup.elise.companion

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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

private val DAYS_LABELS = arrayOf("Tous les jours", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
private val DAYS_VALUES = intArrayOf(-1, 0, 1, 2, 3, 4, 5, 6)
private val HOURS_LABELS = Array(12) { i -> "${i * 2}h – ${i * 2 + 2}h" }

private val TRIGGER_TYPES = arrayOf("Horaire", "Biométrie (FC)", "Mot-clé", "Inactivité")
private val TRIGGER_TYPE_IDS = arrayOf("schedule", "hr_threshold", "keyword", "inactivity")

data class Routine(
    val id: String,
    val description: String,
    val confirmed: Boolean,
    val hourSlot: Int,
    val dayOfWeek: Int,
    val occurrences: Int,
    val manual: Boolean = false,
    val pausedUntil: String? = null,
)

data class Trigger(
    val id: String,
    val name: String,
    val conditionType: String,
    val condition: JSONObject,
    val action: String,
    val active: Boolean,
)

class RoutinesFragment : Fragment() {

    private var _binding: FragmentRoutinesBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private var showRoutines = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRoutinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvItems.layoutManager = LinearLayoutManager(requireContext())

        binding.tabRoutines.setOnClickListener { switchTab(true) }
        binding.tabTriggers.setOnClickListener { switchTab(false) }
        binding.btnRefresh.setOnClickListener { reload() }
        binding.fab.setOnClickListener { if (showRoutines) showAddRoutineDialog() else showAddTriggerDialog() }

        reload()
    }

    private fun switchTab(routines: Boolean) {
        showRoutines = routines
        binding.tabRoutines.setTextColor(if (routines) 0xFF00E5FF.toInt() else 0xFF335566.toInt())
        binding.tabTriggers.setTextColor(if (!routines) 0xFF00E5FF.toInt() else 0xFF335566.toInt())
        binding.tabRoutines.setBackgroundResource(if (routines) R.drawable.tab_selected else R.drawable.tab_unselected)
        binding.tabTriggers.setBackgroundResource(if (!routines) R.drawable.tab_selected else R.drawable.tab_unselected)
        binding.tvSubtitle.text = if (routines) "Habitudes détectées automatiquement" else "Conditions → Actions automatiques"
        reload()
    }

    private fun reload() {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = "Chargement…"
        binding.rvItems.adapter = null
        if (showRoutines) loadRoutines() else loadTriggers()
    }

    // ── Routines ──────────────────────────────────────────────────────────────

    private fun loadRoutines() {
        scope.launch {
            val routines = withContext(Dispatchers.IO) { fetchRoutines() }
            if (routines.isEmpty()) {
                binding.tvEmpty.text = "Aucune routine détectée.\nParle à Élise régulièrement ou crée une routine manuellement."
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvItems.adapter = RoutineAdapter(
                    routines,
                    onToggle = { id, c -> patchRoutine(id, c) },
                    onPause  = { id, scope -> deleteRoutine(id, scope) },
                    onDelete = { id -> deleteRoutine(id, "permanent") },
                )
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
                Routine(
                    id = o.getString("id"),
                    description = o.getString("description"),
                    confirmed = o.optBoolean("confirmed", false),
                    hourSlot = o.optInt("hour_slot", -1),
                    dayOfWeek = o.optInt("day_of_week", -1),
                    occurrences = o.optInt("occurrences", 0),
                    manual = o.optBoolean("manual", false),
                    pausedUntil = o.optString("paused_until", null),
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun patchRoutine(id: String, confirmed: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = """{"confirmed":$confirmed}""".toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/routines/$id")
                    .header("Authorization", "Bearer $TOKEN")
                    .patch(body).build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    private fun deleteRoutine(id: String, deleteScope: String) {
        scope.launch(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/routines/$id?scope=$deleteScope")
                    .header("Authorization", "Bearer $TOKEN")
                    .delete().build()).execute().close()
                withContext(Dispatchers.Main) { loadRoutines() }
            } catch (_: Exception) {}
        }
    }

    private fun showAddRoutineDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_routine, null)
        val etDesc   = view.findViewById<EditText>(R.id.etDescription)
        val spDay    = view.findViewById<Spinner>(R.id.spinnerDay)
        val spHour   = view.findViewById<Spinner>(R.id.spinnerHour)
        val btnOk    = view.findViewById<TextView>(R.id.btnCreate)
        val btnCancel= view.findViewById<TextView>(R.id.btnCancel)

        spDay.adapter  = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, DAYS_LABELS).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spHour.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, HOURS_LABELS).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val dlg = AlertDialog.Builder(requireContext(), R.style.DialogDark)
            .setView(view).create()

        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            val desc = etDesc.text.toString().trim()
            if (desc.isBlank()) { etDesc.error = "Requis"; return@setOnClickListener }
            val dayValue  = DAYS_VALUES[spDay.selectedItemPosition]
            val hourSlot  = spHour.selectedItemPosition
            scope.launch(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("description", desc)
                        put("day_of_week", dayValue)
                        put("hour_slot", hourSlot)
                    }.toString().toRequestBody("application/json".toMediaType())
                    http.newCall(Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}/api/routines")
                        .header("Authorization", "Bearer $TOKEN")
                        .post(body).build()).execute().close()
                    withContext(Dispatchers.Main) { dlg.dismiss(); loadRoutines() }
                } catch (_: Exception) { withContext(Dispatchers.Main) { dlg.dismiss() } }
            }
        }
        dlg.show()
    }

    // ── Triggers ─────────────────────────────────────────────────────────────

    private fun loadTriggers() {
        scope.launch {
            val triggers = withContext(Dispatchers.IO) { fetchTriggers() }
            if (triggers.isEmpty()) {
                binding.tvEmpty.text = "Aucun déclencheur.\nAppuie sur + pour en créer un."
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvItems.adapter = TriggerAdapter(
                    triggers,
                    onToggle = { id, active -> patchTrigger(id, active) },
                    onDelete = { id -> deleteTrigger(id) },
                )
            }
        }
    }

    private fun fetchTriggers(): List<Trigger> = try {
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/triggers")
            .header("Authorization", "Bearer $TOKEN")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(resp.body!!.string()).getJSONArray("triggers")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trigger(
                    id            = o.getString("id"),
                    name          = o.optString("name", ""),
                    conditionType = o.optString("condition_type", "schedule"),
                    condition     = o.optJSONObject("condition") ?: JSONObject(),
                    action        = o.optString("action", ""),
                    active        = o.optBoolean("active", true),
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun patchTrigger(id: String, active: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = """{"active":$active}""".toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/triggers/$id")
                    .header("Authorization", "Bearer $TOKEN")
                    .patch(body).build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    private fun deleteTrigger(id: String) {
        scope.launch(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/triggers/$id")
                    .header("Authorization", "Bearer $TOKEN")
                    .delete().build()).execute().close()
                withContext(Dispatchers.Main) { loadTriggers() }
            } catch (_: Exception) {}
        }
    }

    private fun showAddTriggerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_trigger, null)
        val etName     = view.findViewById<EditText>(R.id.etTriggerName)
        val spType     = view.findViewById<Spinner>(R.id.spinnerConditionType)
        val layoutSch  = view.findViewById<View>(R.id.layoutSchedule)
        val layoutCond = view.findViewById<View>(R.id.layoutConditionValue)
        val tvCondLabel= view.findViewById<TextView>(R.id.tvConditionLabel)
        val etCondVal  = view.findViewById<EditText>(R.id.etConditionValue)
        val spDay      = view.findViewById<Spinner>(R.id.spinnerTriggerDay)
        val spHour     = view.findViewById<Spinner>(R.id.spinnerTriggerHour)
        val etAction   = view.findViewById<EditText>(R.id.etTriggerAction)
        val btnOk      = view.findViewById<TextView>(R.id.btnTriggerCreate)
        val btnCancel  = view.findViewById<TextView>(R.id.btnTriggerCancel)

        spType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, TRIGGER_TYPES).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spDay.adapter  = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, DAYS_LABELS).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spHour.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, Array(24) { "$it:00" }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                when (TRIGGER_TYPE_IDS[pos]) {
                    "schedule" -> { layoutSch.visibility = View.VISIBLE; layoutCond.visibility = View.GONE }
                    "hr_threshold" -> {
                        layoutSch.visibility = View.GONE; layoutCond.visibility = View.VISIBLE
                        tvCondLabel.text = "Seuil FC (ex: >150 ou <50)"
                        etCondVal.hint = "> 150"
                        etCondVal.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                    "keyword" -> {
                        layoutSch.visibility = View.GONE; layoutCond.visibility = View.VISIBLE
                        tvCondLabel.text = "Mot(s)-clé(s) (séparés par virgule)"
                        etCondVal.hint = "réunion, meeting, rendez-vous"
                        etCondVal.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                    "inactivity" -> {
                        layoutSch.visibility = View.GONE; layoutCond.visibility = View.VISIBLE
                        tvCondLabel.text = "Inactivité pendant (minutes)"
                        etCondVal.hint = "60"
                        etCondVal.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                }
            }
        }

        val dlg = AlertDialog.Builder(requireContext(), R.style.DialogDark)
            .setView(view).create()

        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            val name   = etName.text.toString().trim()
            val action = etAction.text.toString().trim()
            if (name.isBlank())   { etName.error = "Requis"; return@setOnClickListener }
            if (action.isBlank()) { etAction.error = "Requis"; return@setOnClickListener }

            val typeId = TRIGGER_TYPE_IDS[spType.selectedItemPosition]
            val condition = buildCondition(typeId, spDay, spHour, etCondVal)

            scope.launch(Dispatchers.IO) {
                try {
                    val body = JSONObject().apply {
                        put("name", name)
                        put("condition_type", typeId)
                        put("condition", condition)
                        put("action", action)
                        put("active", true)
                    }.toString().toRequestBody("application/json".toMediaType())
                    http.newCall(Request.Builder()
                        .url("${BuildConfig.API_BASE_URL}/api/triggers")
                        .header("Authorization", "Bearer $TOKEN")
                        .post(body).build()).execute().close()
                    withContext(Dispatchers.Main) { dlg.dismiss(); loadTriggers() }
                } catch (_: Exception) { withContext(Dispatchers.Main) { dlg.dismiss() } }
            }
        }
        dlg.show()
    }

    private fun buildCondition(typeId: String, spDay: Spinner, spHour: Spinner, etVal: EditText): JSONObject {
        return when (typeId) {
            "schedule" -> JSONObject().apply {
                put("day_of_week", DAYS_VALUES[spDay.selectedItemPosition])
                put("hour", spHour.selectedItemPosition)
            }
            "hr_threshold" -> {
                val expr = etVal.text.toString().trim()
                val op   = if (expr.startsWith("<")) "<" else ">"
                val value = expr.replace(">", "").replace("<", "").trim().toIntOrNull() ?: 150
                JSONObject().apply { put("operator", op); put("value", value) }
            }
            "keyword" -> JSONObject().apply {
                put("keywords", JSONArray(etVal.text.toString().split(",").map { it.trim() }))
            }
            "inactivity" -> JSONObject().apply {
                put("minutes", etVal.text.toString().toIntOrNull() ?: 60)
            }
            else -> JSONObject()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}

// ── Adapters ──────────────────────────────────────────────────────────────────

class RoutineAdapter(
    private val items: List<Routine>,
    private val onToggle: (String, Boolean) -> Unit,
    private val onPause:  (String, String) -> Unit,
    private val onDelete: (String) -> Unit,
) : RecyclerView.Adapter<RoutineAdapter.VH>() {

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val desc:        TextView = view.findViewById(R.id.tvRoutineDesc)
        val meta:        TextView = view.findViewById(R.id.tvRoutineMeta)
        val toggle:      Switch   = view.findViewById(R.id.switchRoutine)
        val btnMore:     TextView = view.findViewById(R.id.btnMore)
        val rowActions:  View     = view.findViewById(R.id.rowActions)
        val btnPauseDay: TextView = view.findViewById(R.id.btnPauseDay)
        val btnPauseWeek:TextView = view.findViewById(R.id.btnPauseWeek)
        val btnPauseMon: TextView = view.findViewById(R.id.btnPauseMonth)
        val btnDelete:   TextView = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_routine, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val r = items[pos]
        vh.desc.text = r.description
        val day = if (r.dayOfWeek in 0..6) DAYS_LABELS[r.dayOfWeek + 1] else "Quotidien"
        val h = if (r.hourSlot >= 0) "${r.hourSlot * 2}h–${r.hourSlot * 2 + 2}h" else "—"
        val src = if (r.manual) "Manuel" else "${r.occurrences}× détecté"
        vh.meta.text = "$day · $h · $src"

        if (r.pausedUntil != null) {
            vh.desc.alpha = 0.4f
            vh.meta.text = "⏸ Suspendu jusqu'au ${r.pausedUntil.take(10)}"
        } else {
            vh.desc.alpha = 1f
        }

        vh.toggle.setOnCheckedChangeListener(null)
        vh.toggle.isChecked = r.confirmed
        vh.toggle.setOnCheckedChangeListener { _, checked -> onToggle(r.id, checked) }

        vh.rowActions.visibility = View.GONE
        vh.btnMore.setOnClickListener {
            vh.rowActions.visibility = if (vh.rowActions.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        vh.btnPauseDay.setOnClickListener  { onPause(r.id, "day");   vh.rowActions.visibility = View.GONE }
        vh.btnPauseWeek.setOnClickListener { onPause(r.id, "week");  vh.rowActions.visibility = View.GONE }
        vh.btnPauseMon.setOnClickListener  { onPause(r.id, "month"); vh.rowActions.visibility = View.GONE }
        vh.btnDelete.setOnClickListener    { onDelete(r.id);         vh.rowActions.visibility = View.GONE }
    }
}

class TriggerAdapter(
    private val items: List<Trigger>,
    private val onToggle: (String, Boolean) -> Unit,
    private val onDelete: (String) -> Unit,
) : RecyclerView.Adapter<TriggerAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name:      TextView = view.findViewById(R.id.tvTriggerName)
        val condition: TextView = view.findViewById(R.id.tvTriggerCondition)
        val action:    TextView = view.findViewById(R.id.tvTriggerAction)
        val toggle:    Switch   = view.findViewById(R.id.switchTrigger)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteTrigger)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_trigger, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val t = items[pos]
        vh.name.text = t.name
        vh.condition.text = formatCondition(t)
        vh.action.text = "→ ${t.action}"

        vh.toggle.setOnCheckedChangeListener(null)
        vh.toggle.isChecked = t.active
        vh.toggle.setOnCheckedChangeListener { _, checked -> onToggle(t.id, checked) }
        vh.btnDelete.setOnClickListener { onDelete(t.id) }
    }

    private fun formatCondition(t: Trigger): String = when (t.conditionType) {
        "schedule" -> {
            val day  = t.condition.optInt("day_of_week", -1)
            val hour = t.condition.optInt("hour", 0)
            val dayStr = if (day in 0..6) DAYS_LABELS[day + 1] else "Tous les jours"
            "⏰ $dayStr à ${hour}h00"
        }
        "hr_threshold" -> {
            val op  = t.condition.optString("operator", ">")
            val val2 = t.condition.optInt("value", 150)
            "♥ FC $op $val2 bpm"
        }
        "keyword" -> {
            val kws = t.condition.optJSONArray("keywords")
            val list = (0 until (kws?.length() ?: 0)).joinToString(", ") { kws!!.getString(it) }
            "🔤 Mots-clés: $list"
        }
        "inactivity" -> "💤 Inactivité > ${t.condition.optInt("minutes", 60)} min"
        else -> t.conditionType
    }
}
