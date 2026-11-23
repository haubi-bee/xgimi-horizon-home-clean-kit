package tv.cleanhome.launcher.apps

/** Pure scoring logic for usage frequency (no Android dependencies). Scores are keyed by tile key. */
object UsageScores {
    const val DECAY = 0.97
    const val LAUNCH_BONUS = 1.0

    /** Scores below this threshold are dropped (roughly 150 launches without using that tile). */
    const val MIN_SCORE = 0.01

    /** On every launch: all scores × [DECAY], the launched tile gets + [LAUNCH_BONUS]. */
    fun afterLaunch(scores: Map<String, Double>, launchedKey: String): Map<String, Double> {
        val result = HashMap<String, Double>(scores.size + 1)
        for ((key, score) in scores) {
            val decayed = score * DECAY
            if (decayed >= MIN_SCORE) result[key] = decayed
        }
        result[launchedKey] = (result[launchedKey] ?: 0.0) + LAUNCH_BONUS
        return result
    }
}
