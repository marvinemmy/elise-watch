package com.lnsgroup.elise.watch

object Config {
    const val API_BASE_URL = "https://lnsgroup.dev"

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
    // Réglage conservateur pour micro montre (sensibilité plus faible qu'un téléphone)
    const val SILENCE_THRESHOLD_RMS = 200     // en dessous = silence (abaissé pour environnements calmes)
    const val SILENCE_DURATION_MS   = 600L    // 600ms silence = fin de phrase (augmenté pour éviter les coupures)
    const val MAX_RECORD_MS         = 12_000L // sécurité : max 12 secondes

    // Wake word TFLite
    const val WAKE_WORD_MODEL = "wake_word.tflite"
    const val WAKE_WORD_THRESHOLD = 0.80f     // confiance minimale
    const val WAKE_WORD_WINDOW_MS = 1000      // fenêtre d'analyse 1s
    const val WAKE_WORD_COOLDOWN_MS = 3000L   // attente entre deux détections

    // Vibrations — uniquement pour PROCESSING
    const val VIB_PROCESSING_PULSE = 60L   // pulse pendant le traitement
    const val VIB_PROCESSING_INTERVAL = 900L  // intervalle entre pulses

    // VAD — déclenchement enregistrement par voix dans état LISTENING
    // Abaissé pour micro montre (distance ~20cm, sensibilité réduite vs téléphone)
    const val VAD_THRESHOLD_RMS  = 900f    // était 2000 — voix normale à 20cm dépasse 900 RMS
    const val VAD_TRIGGER_MS     = 150L    // 150ms de voix continue déclenche l'enregistrement

    // VAD — interruption pendant SPEAKING (seuil plus haut pour ignorer le haut-parleur)
    const val VAD_INTERRUPT_RMS  = 2200f   // ajusté proportionnellement au nouveau VAD_THRESHOLD
    const val VAD_INTERRUPT_MS   = 250L    // 250ms de voix = interruption confirmée

    // Silence → retour WAITING depuis LISTENING
    const val SILENCE_TO_WAIT_MS = 10_000L  // 10s d'écoute après réponse avant retour WAITING

    // ── Diagnostic micro ──────────────────────────────────────────────────────
    // Activer pour logger le RMS en continu et calibrer les seuils
    const val MIC_DIAGNOSTIC_MODE = false

    // Mots/phrases de désactivation vocale (transcript Whisper normalisé lowercase, trim ponctuation fin)
    // Inclus avec et sans virgule pour couvrir les variantes de transcription Whisper
    val STOP_WORDS = setOf(
        // ── FR — désactivation ─────────────────────────────────────────────
        "élise off", "elise off",
        "élise, dors", "élise dors",
        "élise, au revoir", "élise au revoir",
        "élise, repos", "élise repos",
        "élise, bonne nuit", "élise bonne nuit",
        "élise, déconnecte", "élise déconnecte",
        "élise, en veille", "élise en veille",
        "élise, c'est tout", "élise c'est tout",
        "élise, merci, stop", "élise merci stop",
        "élise, ferme-toi", "élise ferme-toi",
        "élise, silence", "élise silence",
        "réveille-toi non", // sécurité anti-faux-positif
        // ── EN — deactivation ──────────────────────────────────────────────
        "elise, sleep", "elise sleep",
        "elise, goodbye", "elise goodbye",
        "elise, rest", "elise rest",
        "elise, good night", "elise good night",
        "elise, disconnect", "elise disconnect",
        "elise, standby", "elise standby",
        "elise, that's all", "elise that's all",
        "elise, thank you, stop", "elise thank you stop",
        "elise, shut down", "elise shut down",
        "elise, silence", "elise silence",
        // ── Mots courts universels ─────────────────────────────────────────
        "stop", "arrête", "arrêtes", "arrêter", "annule", "annuler",
        "silence", "tais-toi", "tais toi", "ça suffit", "ca suffit",
        "au revoir", "bonne nuit",
    )

    // Wake phrase "Daddy's home" — detection périodique en état WAITING
    const val WAKE_PHRASE_CHECK_INTERVAL_MS = 3000L   // toutes les 3s
    const val WAKE_PHRASE_SAMPLE_MS         = 600      // 600ms d'audio
    const val WAKE_PHRASE_MIN_RMS           = 400f     // seuil d'énergie minimum
    val WAKE_PHRASES = setOf(
        "daddy's home", "daddys home", "daddy home",
        "wake up daddy's home", "wake up daddy",
        "daddy est là", "daddy est arrivé",
    )

    // Préférences
    const val PREF_FILE = "elise_prefs"
    const val KEY_TOKEN = "elise_token"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
    const val KEY_DEVICE_ID = "device_id"
}
