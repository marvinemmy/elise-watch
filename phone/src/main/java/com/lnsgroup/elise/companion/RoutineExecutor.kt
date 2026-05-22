package com.lnsgroup.elise.companion

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "RoutineExec"

object RoutineExecutor {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun execute(context: Context, routineId: String) {
        val prompt = buildPrompt(context, routineId) ?: run {
            Log.d(TAG, "Routine $routineId skipped (condition not met)")
            return
        }
        Log.i(TAG, "Executing routine: $routineId")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply { put("prompt", prompt) }
                    .toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/routines/speak")
                    .header("Authorization", "Bearer $TOKEN")
                    .post(body).build()
                val resp = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@launch
                    JSONObject(r.body!!.string())
                }
                val mp3B64 = resp.optString("mp3_base64", "")
                val responseText = resp.optString("response_text", "")
                if (mp3B64.isNotBlank()) {
                    val mp3 = android.util.Base64.decode(mp3B64, android.util.Base64.DEFAULT)
                    playMp3(context, mp3)
                }
                handlePostRoutine(context, routineId, responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Routine $routineId failed: ${e.message}")
            }
        }
    }

    private fun buildPrompt(context: Context, routineId: String): String? {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min  = cal.get(Calendar.MINUTE)
        val dow  = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon … 7=Sat
        val timeStr = String.format("%dh%02d", hour, min)

        // Calendar.DAY_OF_WEEK: Sun=1 Mon=2 … Sat=7 → index Mon=0 … Sun=6
        val workDayIdx = ((dow - 2 + 7) % 7)
        val workToday   = WorkSchedule.isWorkDay(context, workDayIdx)
        val workTomIdx  = ((dow - 1 + 7) % 7)
        val workTomorrow = WorkSchedule.getTomorrowWork(context) ?: WorkSchedule.isWorkDay(context, workTomIdx)
        val homeAddr    = WorkSchedule.getHomeAddress(context).takeIf { it.isNotBlank() }
        val workAddr    = WorkSchedule.getWorkAddress(context).takeIf { it.isNotBlank() }
        val needsFuel   = WorkSchedule.needsFuel(context)
        val shoppingList = WorkSchedule.formatShoppingListForElise(context)
        val isVacation  = WorkSchedule.isVacation(context)

        return when (routineId) {

            RoutineScheduler.ROUTINE_MORNING -> {
                val trafficLine = if (workToday && homeAddr != null && workAddr != null)
                    "Vérifie s'il y a des infos trafic/accidents/travaux entre '$homeAddr' et '$workAddr'."
                else ""
                val fuelLine = if (needsFuel || dow == Calendar.FRIDAY)
                    "Rappelle que l'utilisateur doit faire le plein d'essence aujourd'hui."
                else ""
                val rentLine = if (dow == Calendar.FRIDAY)
                    "C'est vendredi : rappelle de faire le virement du loyer (il doit répondre 'check' pour confirmer)."
                else ""
                """
Tu es Élise, assistante IA chaleureuse. Génère le rapport matinal oral et concis.
Heure actuelle : $timeStr.
L'utilisateur travaille aujourd'hui : ${if (workToday) "oui" else "non"}.
Dans l'ordre :
1. Dis bonjour et l'heure
2. Donne la météo actuelle (utilise ton outil météo)
3. Mentionne si c'est une journée de travail ou de repos
4. ${if (workToday) "Infos trajet si disponible. $trafficLine" else "Bonne journée de repos."}
5. Nombre d'emails non-lus et sujets importants si disponibles
6. Rappels agenda du jour si disponibles
$fuelLine
$rentLine
Sois naturelle, concise, comme si tu réveillais un ami.
                """.trimIndent()
            }

            RoutineScheduler.ROUTINE_DEPARTURE -> {
                val items = listOf(
                    "téléphone principal", "téléphone secondaire", "clé de voiture",
                    "clé de maison", "power bank", "vape", "portefeuille", "sac",
                    "lunch", "Dharppy", "couteau"
                )
                """
Tu es Élise. Lance la checklist de départ au travail.
Présente chaque item UN PAR UN en disant "Est-ce que tu as ton [item] ?".
Attends "check" ou "oui" avant de passer au suivant.
Si l'utilisateur dit "non" ou hésite, note l'item manquant.
Liste : ${items.joinToString(", ")}.
Quand tout est confirmé : "Parfait, tu es prêt à partir ! Bonne journée au travail."
Si des items manquent, rappelle-les à la fin.
                """.trimIndent()
            }

            RoutineScheduler.ROUTINE_EVENING_CHECK -> {
                if (isVacation) return null
                """
Tu es Élise. Demande simplement : "Est-ce que tu travailles demain ?"
Attends la réponse et dis bonne nuit chaleureusement.
Sois brève, naturelle.
                """.trimIndent()
            }

            RoutineScheduler.ROUTINE_FUEL_EVENING -> {
                if (!workToday && dow != Calendar.WEDNESDAY && dow != Calendar.THURSDAY) return null
                """
Tu es Élise. Demande : "Est-ce que tu dois faire le plein d'essence ce soir ?"
Si oui, confirme que tu le rappelleras demain matin.
Sois très brève.
                """.trimIndent()
            }

            RoutineScheduler.ROUTINE_SHOPPING -> {
                val listText = if (shoppingList.isNotBlank()) shoppingList else "liste non configurée"
                """
Tu es Élise. Rappelle à l'utilisateur d'aller faire les courses.
Waitrose ferme à 16h00 — il faut y être avant 15h00.
Liste de courses : $listText.
Souhaite-lui de bonnes courses. Sois chaleureuse et courte.
                """.trimIndent()
            }

            else -> null
        }
    }

    private fun handlePostRoutine(context: Context, routineId: String, response: String) {
        val lower = response.lowercase()
        when (routineId) {
            RoutineScheduler.ROUTINE_EVENING_CHECK -> {
                val willWork = "oui" in lower || "travaille" in lower || "bonne nuit" !in lower
                WorkSchedule.setTomorrowWork(context, willWork)
            }
            RoutineScheduler.ROUTINE_FUEL_EVENING -> {
                val needsFuel = "oui" in lower || "plein" in lower || "rappellera" in lower
                WorkSchedule.setNeedsFuel(context, needsFuel)
            }
        }
    }

    private fun playMp3(context: Context, mp3: ByteArray) {
        try {
            val tmp = File(context.cacheDir, "routine_response.mp3")
            tmp.writeBytes(mp3)
            val player = MediaPlayer()
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
        }
    }
}
