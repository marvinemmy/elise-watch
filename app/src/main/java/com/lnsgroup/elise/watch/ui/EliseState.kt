package com.lnsgroup.elise.watch.ui

enum class EliseState {
    WAITING,        // veille active — violet/rose
    LISTENING,      // écoute wake word — cyan
    RECORDING,      // enregistrement — cyan vif
    PROCESSING,     // traitement — orange
    SPEAKING,       // réponse — jaune
    ERROR,          // erreur — rouge
    IDLE,
    NOT_CONFIGURED,
}
