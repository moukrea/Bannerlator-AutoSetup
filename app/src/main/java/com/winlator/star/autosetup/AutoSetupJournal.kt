package com.winlator.star.autosetup

import android.content.Context
import org.json.JSONObject

class AutoSetupJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(gameKey: String): AutoSetupStatus? {
        val raw = prefs.getString(key(gameKey), null) ?: return null
        return runCatching { AutoSetupStatus.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun write(status: AutoSetupStatus) {
        prefs.edit().putString(key(status.gameKey), status.toJson().toString()).apply()
    }

    fun transition(current: AutoSetupStatus, next: AutoSetupStage, detail: String = ""): AutoSetupStatus {
        require(current.stage == next || AutoSetupTransitions.canTransition(current.stage, next)) {
            "Invalid auto-setup transition ${current.stage} -> $next"
        }
        return current.copy(stage = next, detail = detail, updatedAt = System.currentTimeMillis()).also(::write)
    }

    private fun key(gameKey: String) = "status_${gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
    private companion object { const val PREFS = "auto_setup_journal" }
}
