package com.lnsgroup.elise.watch.service

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lnsgroup.elise.watch.Config
import com.lnsgroup.elise.watch.audio.AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val TAG = "WatchRoutineExecutor"

object WatchRoutineExecutor {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun execute(context: Context, routineId: String) {
        val prompt = buildPrompt(context, routineId) ?: run {
            Log.i(TAG, "Routine $routineId skipped (condition not met)")
            return
        }
        Log.i(TAG, "Executing routine $routineId")

        try {
            val mp3 = withContext(Dispatchers.IO) { callRoutineSpeak(prompt) } ?: return
            val player = AudioPlayer(context.cacheDir)
            player.playMp3(mp3)
            handlePostRoutine(context, routineId)
        } catch (e: Exception) {
            Log.e(TAG, "Routine $routineId failed: ${e.message}")
        }
    }

    private fun callRoutineSpeak(prompt: String): ByteArray? {
        val body = JSONObject().apply { put("prompt", prompt) }.toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${Config.API_BASE_URL}/api/routines/speak")
            .header("Authorization", "Bearer ${Config.PRELOADED_TOKEN}")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "Server error ${resp.code}")
                return null
            }
            val json = JSONObject(resp.body!!.string())
            val b64 = json.optString("mp3_base64") ?: return null
            return Base64.decode(b64, Base64.DEFAULT)
        }
    }

    private fun buildPrompt(context: Context, routineId: String): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val dow = now.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        val isVacation = WatchRoutineScheduler.isVacation(context)

        return when (routineId) {

            WatchRoutineScheduler.ROUTINE_MORNING -> {
                val workToday = if (isVacation) false else WatchRoutineScheduler.isWorkDay(context, dow)
                val isFriday = dow == Calendar.FRIDAY
                val needsFuel = WatchRoutineScheduler.needsFuel(context)

                buildString {
                    append("Rapport du matin — il est ${hour}h${minute.toString().padStart(2,'0')}. ")
                    append("Donne-moi : l'heure exacte, la météo actuelle à Worthing UK, ")
                    if (workToday) {
                        append("état de la circulation entre Worthing et Havant (infos trafic, accidents, travaux), ")
                    }
                    append("nombre d'emails non lus et leurs sujets importants, ")
                    append("mes rendez-vous du jour s'il y en a. ")
                    if (isFriday) {
                        append("Rappelle-moi de faire le virement de loyer aujourd'hui. ")
                        if (needsFuel) append("Rappelle-moi aussi de faire le plein d'essence. ")
                    }
                    if (!workToday && !isVacation) {
                        append("C'est mon jour de repos. ")
                    }
                    append("Sois bref et direct.")
                }
            }

            WatchRoutineScheduler.ROUTINE_EVENING_CHECK -> {
                if (isVacation) return null
                val tomorrowDow = if (dow == Calendar.SATURDAY) Calendar.SUNDAY
                    else (dow % 7) + 1
                val workTomorrow = WatchRoutineScheduler.isWorkDay(context, tomorrowDow)
                "Demande-moi si je travaille demain. " +
                    "Selon ma réponse (check = oui, non merci = non), mets à jour mon planning pour demain. " +
                    "Sois très bref."
            }

            WatchRoutineScheduler.ROUTINE_FUEL_EVENING -> {
                val dayName = if (dow == Calendar.WEDNESDAY) "mercredi" else "jeudi"
                "C'est $dayName soir. Demande-moi si je dois faire le plein ce soir ou ce week-end. " +
                    "Une seule question courte."
            }

            WatchRoutineScheduler.ROUTINE_SHOPPING -> {
                val day = if (dow == Calendar.SATURDAY) "Samedi" else "Dimanche"
                "$day matin — rappelle-moi d'aller faire les courses à Waitrose avant 15h " +
                    "(ferme à 16h). Sois très bref."
            }

            else -> null
        }
    }

    private fun handlePostRoutine(context: Context, routineId: String) {
        when (routineId) {
            WatchRoutineScheduler.ROUTINE_FUEL_EVENING ->
                WatchRoutineScheduler.setNeedsFuel(context, true)
        }
    }
}
