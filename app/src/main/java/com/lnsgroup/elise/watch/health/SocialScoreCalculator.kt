package com.lnsgroup.elise.watch.health

object SocialScoreCalculator {

    fun compute(
        hrBpm: Int,
        stress: Float,
        agitation: Float,
        fatigue: Float,
        steps: Long,
    ): Int {
        val hrPts = when {
            hrBpm <= 0   -> 15
            hrBpm < 50   -> 10
            hrBpm <= 65  -> 30
            hrBpm <= 75  -> 26
            hrBpm <= 85  -> 20
            hrBpm <= 95  -> 13
            hrBpm <= 115 -> 7
            else         -> 3
        }
        val calmPts   = ((100f - stress)    / 100f * 25f).toInt()
        val actPts    = when {
            steps >= 8000 -> 20; steps >= 5000 -> 16; steps >= 2000 -> 12
            steps >= 500  -> 7;  else          -> 3
        }
        val serenPts  = ((100f - agitation) / 100f * 15f).toInt()
        val energyPts = ((100f - fatigue)   / 100f * 10f).toInt()
        return (hrPts + calmPts + actPts + serenPts + energyPts).coerceIn(0, 100)
    }

    fun label(score: Int): String = when {
        score >= 85 -> "OPTIMAL"
        score >= 70 -> "BON"
        score >= 50 -> "MODÉRÉ"
        score >= 30 -> "FATIGUÉ"
        else        -> "CRITIQUE"
    }

    fun color(score: Int): Int = when {
        score >= 85 -> 0xFF00FF88.toInt()
        score >= 70 -> 0xFF00E5FF.toInt()
        score >= 50 -> 0xFFFFDD00.toInt()
        score >= 30 -> 0xFFFF9500.toInt()
        else        -> 0xFFFF2244.toInt()
    }
}
