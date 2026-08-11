package com.winlator.star.autosetup

import android.content.Context
import org.json.JSONObject

data class AutoBenchmarkSummary(
    val gameKey: String,
    val durationMs: Long,
    val presentedFrames: Long,
    val averageFps: Float,
    val stable: Boolean,
    val recordedAt: Long,
) {
    fun toJson() = JSONObject().apply {
        put("gameKey", gameKey)
        put("durationMs", durationMs)
        put("presentedFrames", presentedFrames)
        put("averageFps", averageFps.toDouble())
        put("stable", stable)
        put("recordedAt", recordedAt)
    }
}

class AutoBenchmarkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(gameKey: String): AutoBenchmarkSummary? {
        val raw = prefs.getString(key(gameKey), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            AutoBenchmarkSummary(
                gameKey = gameKey,
                durationMs = json.getLong("durationMs"),
                presentedFrames = json.getLong("presentedFrames"),
                averageFps = json.getDouble("averageFps").toFloat(),
                stable = json.getBoolean("stable"),
                recordedAt = json.getLong("recordedAt"),
            )
        }.getOrNull()
    }

    fun write(summary: AutoBenchmarkSummary) {
        prefs.edit().putString(key(summary.gameKey), summary.toJson().toString()).apply()
    }

    private fun key(gameKey: String) = "benchmark_${gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    private companion object { const val PREFS = "auto_setup_benchmarks" }
}
