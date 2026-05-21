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
    const val SILENCE_DURATION_MS = 1800L     // silence de 1.8s = fin de phrase
    const val MAX_RECORD_MS = 12_000L         // sécurité : max 12 secondes

    // Wake word TFLite
    const val WAKE_WORD_MODEL = "wake_word.tflite"
    const val WAKE_WORD_THRESHOLD = 0.80f     // confiance minimale
    const val WAKE_WORD_WINDOW_MS = 1000      // fenêtre d'analyse 1s
    const val WAKE_WORD_COOLDOWN_MS = 3000L   // attente entre deux détections

    // Vibrations
    const val VIB_WAKE  = 80L    // courte — "je t'écoute"
    const val VIB_SEND  = 40L    // très courte — "j'envoie"
    const val VIB_ERROR = 200L   // longue — erreur

    // Préférences
    const val PREF_FILE = "elise_prefs"
    const val KEY_TOKEN = "elise_token"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
    const val KEY_DEVICE_ID = "device_id"
}
