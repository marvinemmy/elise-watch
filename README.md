# ÉLISE Watch — Application Samsung Galaxy Watch 5

Application Wear OS pour communiquer vocalement avec ÉLISE.

## Architecture

```
Montre (Wear OS 3.5 — Kotlin)
  │  Écoute permanente "Ok Élise"
  │  AudioRecord → TFLite wake word
  │  WAV → WebSocket → wss://lnsgroup.dev/ws/voice?token=...
  │
Serveur ÉLISE (ta machine + Cloudflare Tunnel)
  │  Whisper STT → llama3.2 → edge-tts Microsoft Neural
  │  MP3 → WebSocket → Montre
  │
Montre
  └  MediaPlayer → haut-parleur
```

## Prérequis

- Android Studio Hedgehog (2023.1.1) ou plus récent
- JDK 17
- Wear OS Emulator **ou** Samsung Galaxy Watch 5 en mode développeur

## Installation Android Studio

Télécharge sur https://developer.android.com/studio

## Ouvrir le projet

1. Android Studio → **Open**
2. Sélectionne `C:\Users\User\Desktop\Elise\elise-watch`
3. Attends la synchronisation Gradle (télécharge ~500MB de dépendances)

## Configuration avant compilation

### 1. Obtenir le token ÉLISE

- Ouvre http://localhost:8000/dashboard/
- Onglet **Licenses** → Clique **NEW LICENSE** → plan Premium → 365 jours
- Copie le `license_uuid` généré

### 2. Configurer le token dans l'app

Ouvre [Config.kt](app/src/main/java/com/lnsgroup/elise/watch/Config.kt) et
modifie la constante `WS_URL` pour pointer vers ton serveur :
```kotlin
const val WS_URL = "wss://lnsgroup.dev/ws/voice"
```

Le token est entré au premier lancement de l'app dans l'écran de configuration.

## Modèle Wake Word

Le fichier `app/src/main/assets/wake_word.tflite` est le modèle de détection "Ok Élise".

**Pour entraîner un modèle personnalisé :**
```bash
cd C:\Users\User\Desktop\Elise\wake_word
pip install gtts soundfile tensorflow openwakeword
python train_ok_elise.py
# Copie ok_elise.tflite → app/src/main/assets/wake_word.tflite
```

**Sans modèle entraîné :** l'app utilise un fallback basé sur l'amplitude
(appuie le bouton 🎙 sur l'écran de la montre pour déclencher manuellement).

## Compilation

### Émulateur (test rapide)
1. Android Studio → Tools → AVD Manager → Create Virtual Device
2. Choisir **Wear OS 3.5** → Galaxy Watch 4 profile
3. Run → sélectionner l'émulateur

### Montre réelle (Samsung Galaxy Watch 5)
1. Sur la montre : Settings → Developer Options → ADB Debugging → ON
2. Settings → Developer Options → Debug over WiFi → ON
3. Note l'IP affichée (ex: 192.168.1.50:5555)
4. Dans un terminal : `adb connect 192.168.1.50:5555`
5. Android Studio → Run → sélectionner la montre

## Déploiement serveur (Cloudflare Tunnel)

Avant de tester sur la vraie montre, expose le serveur ÉLISE :

```powershell
# Une seule fois : configurer le tunnel
cd C:\Users\User\Desktop\Elise\infra
.\setup_cloudflare_tunnel.ps1

# À chaque démarrage
.\start_elise.ps1
```

## Fonctionnement

| Geste | Action |
|-------|--------|
| Dire "Ok Élise" | Déclenche l'écoute (wake word) |
| 🎙 bouton | Déclenche manuellement |
| ⚙ bouton | Configuration (URL, token) |

## États visuels

| Couleur | État |
|---------|------|
| Cyan pulse | En écoute — en attente du wake word |
| Vert fixe | Enregistrement en cours |
| Or pulse | Traitement / Réponse en cours |
| Rouge | Erreur |
