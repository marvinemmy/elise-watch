package com.lnsgroup.elise.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object WorkSchedule {

    private const val PREFS = "work_schedule"
    private const val KEY_WORK_DAYS   = "work_days"      // JSON array of booleans [Mon..Sun]
    private const val KEY_TOMORROW    = "tomorrow_work"  // null=ask | true=yes | false=no
    private const val KEY_VACATION    = "vacation_mode"
    private const val KEY_HOME_ADDR   = "home_address"
    private const val KEY_WORK_ADDR   = "work_address"
    private const val KEY_FUEL_REMIND = "fuel_remind"    // true = user needs fuel
    private const val KEY_SHOPPING    = "shopping_items" // JSON array [{item, qty, checked}]

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Work days (index 0=Mon … 6=Sun) ──────────────────────────────────────

    fun getWorkDays(context: Context): BooleanArray {
        val json = prefs(context).getString(KEY_WORK_DAYS, null)
        return if (json != null) {
            val arr = JSONArray(json)
            BooleanArray(7) { i -> arr.optBoolean(i, i < 5) }
        } else BooleanArray(7) { i -> i < 5 } // default Mon-Fri
    }

    fun setWorkDays(context: Context, days: BooleanArray) {
        val arr = JSONArray(days.map { it })
        prefs(context).edit().putString(KEY_WORK_DAYS, arr.toString()).apply()
    }

    /** Returns true if today (dayOfWeek 1=Mon…7=Sun) is a work day. */
    fun isWorkDay(context: Context, dayOfWeek: Int): Boolean {
        val idx = ((dayOfWeek - 1 + 7) % 7)  // Calendar.MONDAY=2 → index 0
        return getWorkDays(context)[idx]
    }

    // ── Tomorrow work status (set by 21h routine) ─────────────────────────────

    fun getTomorrowWork(context: Context): Boolean? {
        val raw = prefs(context).getString(KEY_TOMORROW, null) ?: return null
        return raw == "true"
    }

    fun setTomorrowWork(context: Context, work: Boolean) {
        prefs(context).edit().putString(KEY_TOMORROW, work.toString()).apply()
    }

    fun clearTomorrowWork(context: Context) {
        prefs(context).edit().remove(KEY_TOMORROW).apply()
    }

    // ── Vacation mode ──────────────────────────────────────────────────────────

    fun isVacation(context: Context) = prefs(context).getBoolean(KEY_VACATION, false)
    fun setVacation(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_VACATION, v).apply()
    }

    // ── Addresses ─────────────────────────────────────────────────────────────

    fun getHomeAddress(context: Context)  = prefs(context).getString(KEY_HOME_ADDR, "") ?: ""
    fun getWorkAddress(context: Context)  = prefs(context).getString(KEY_WORK_ADDR, "") ?: ""
    fun setHomeAddress(context: Context, addr: String) { prefs(context).edit().putString(KEY_HOME_ADDR, addr).apply() }
    fun setWorkAddress(context: Context, addr: String) { prefs(context).edit().putString(KEY_WORK_ADDR, addr).apply() }

    // ── Fuel reminder ─────────────────────────────────────────────────────────

    fun needsFuel(context: Context) = prefs(context).getBoolean(KEY_FUEL_REMIND, false)
    fun setNeedsFuel(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_FUEL_REMIND, v).apply() }

    // ── Shopping list ─────────────────────────────────────────────────────────

    data class ShoppingItem(val item: String, val qty: Int, val checked: Boolean)

    fun getShoppingList(context: Context): List<ShoppingItem> {
        val json = prefs(context).getString(KEY_SHOPPING, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ShoppingItem(o.getString("item"), o.optInt("qty", 1), o.optBoolean("checked", false))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun setShoppingList(context: Context, list: List<ShoppingItem>) {
        val arr = JSONArray(list.map { JSONObject().apply {
            put("item", it.item); put("qty", it.qty); put("checked", it.checked)
        }})
        prefs(context).edit().putString(KEY_SHOPPING, arr.toString()).apply()
    }

    fun resetShoppingChecks(context: Context) {
        val list = getShoppingList(context).map { it.copy(checked = false) }
        setShoppingList(context, list)
    }

    fun formatShoppingListForElise(context: Context): String {
        val list = getShoppingList(context)
        if (list.isEmpty()) return "La liste de courses est vide."
        return list.joinToString(", ") { "${it.item} x${it.qty}" }
    }
}
