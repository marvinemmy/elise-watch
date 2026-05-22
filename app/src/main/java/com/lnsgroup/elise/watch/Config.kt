package com.lnsgroup.elise.watch

object Config {
    // URL WebSocket du serveur ÉLISE — cloud Oracle permanent (0€)
    const val WS_URL_PROD  = "wss://lnsgroup.dev/ws/voice"
    const val WS_URL       = WS_URL_PROD                          // ← toujours la prod
    const val WS_URL_LOCAL = "ws://192.168.1.100:8000/ws/voice"  // tests locaux uniquement

    // Token preloaded (licence premium 10 ans) — mis à jour automatiquement
    const val PRELOADED_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc19hZG1pbiI6dHJ1ZSwiZW1haWwiOiJtYXJ2aW5wa2VpdGFAZ21haWwuY29tIiwiZXhwIjoyMDk0NzA2MDYzLCJpYXQiOjE3NzkzNDYwNjN9.GXRa93bmRpNNaf7j165OW65sz6A-VSdsxX1J4t6imV8"

    // Audio
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val BUFFER_SECONDS = 0.1f   // taille du buffer d'entrée

    // Détection silence (arrêt enregistrement)
    const val SILENCE_THRESHOLD_RMS = 300     // amplitude RMS en dessous = silence
    const val SILENCE_DURATION_MS = 600L      // silence de 600ms = fin de phrase (was 1800ms)
    const val MAX_RECORD_MS = 12_000L         // sécurité : max 12 secondes

    // Wake word TFLite
    const val WAKE_WORD_MODEL = "wake_word.tflite"
    const val WAKE_WORD_THRESHOLD = 0.80f     // confiance minimale
    const val WAKE_WORD_WINDOW_MS = 1000      // fenêtre d'analyse 1s
    const val WAKE_WORD_COOLDOWN_MS = 3000L   // attente entre deux détections

    // Vibrations — uniquement pour PROCESSING
    const val VIB_PROCESSING_PULSE = 60L   // pulse pendant le traitement
    const val VIB_PROCESSING_INTERVAL = 900L  // intervalle entre pulses

    // VAD — déclenchement enregistrement par voix dans état LISTENING
    const val VAD_THRESHOLD_RMS  = 2000f   // amplitude min pour détecter la parole (voix forte uniquement)
    const val VAD_TRIGGER_MS     = 200L    // durée parole continue avant déclenchement

    // VAD — interruption pendant SPEAKING (seuil plus haut pour ignorer le haut-parleur)
    const val VAD_INTERRUPT_RMS  = 3500f   // plus élevé pour éviter les faux positifs du speaker
    const val VAD_INTERRUPT_MS   = 250L    // 250ms de voix = interruption confirmée

    // Silence → retour WAITING depuis LISTENING
    const val SILENCE_TO_WAIT_MS = 3000L

    // Off word — arrêt vocal (normalisé lowercase, sans ponctuation)
    val STOP_WORDS = setOf(
        "stop", "arrête", "arrêtes", "arrêter", "annule", "annuler",
        "silence", "tais-toi", "tais toi", "ça suffit", "ca suffit"
    )

    // Préférences
    const val PREF_FILE = "elise_prefs"
    const val KEY_TOKEN = "elise_token"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
    const val KEY_DEVICE_ID = "device_id"
}
