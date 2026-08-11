package com.winlator.star.autosetup

import org.json.JSONObject

enum class AutoSetupStage {
    QUEUED, RESOLVING_PROFILE, INSTALLING_COMPONENTS, CREATING_ENVIRONMENT,
    APPLYING_PROFILE, LAUNCHING, VALIDATING, READY, FAILED,
}

object AutoSetupTransitions {
    private val allowed = mapOf(
        AutoSetupStage.QUEUED to setOf(AutoSetupStage.RESOLVING_PROFILE, AutoSetupStage.FAILED),
        AutoSetupStage.RESOLVING_PROFILE to setOf(AutoSetupStage.INSTALLING_COMPONENTS, AutoSetupStage.CREATING_ENVIRONMENT, AutoSetupStage.LAUNCHING, AutoSetupStage.FAILED),
        AutoSetupStage.INSTALLING_COMPONENTS to setOf(AutoSetupStage.CREATING_ENVIRONMENT, AutoSetupStage.FAILED),
        AutoSetupStage.CREATING_ENVIRONMENT to setOf(AutoSetupStage.APPLYING_PROFILE, AutoSetupStage.FAILED),
        AutoSetupStage.APPLYING_PROFILE to setOf(AutoSetupStage.LAUNCHING, AutoSetupStage.FAILED),
        AutoSetupStage.LAUNCHING to setOf(AutoSetupStage.VALIDATING, AutoSetupStage.FAILED),
        AutoSetupStage.VALIDATING to setOf(AutoSetupStage.READY, AutoSetupStage.FAILED),
        AutoSetupStage.READY to setOf(AutoSetupStage.RESOLVING_PROFILE, AutoSetupStage.LAUNCHING),
        AutoSetupStage.FAILED to setOf(AutoSetupStage.RESOLVING_PROFILE),
    )

    fun canTransition(from: AutoSetupStage, to: AutoSetupStage): Boolean = to in allowed.getValue(from)
}

data class AutoSetupStatus(
    val gameKey: String,
    val gameName: String,
    val stage: AutoSetupStage,
    val detail: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val terminal: Boolean get() = stage == AutoSetupStage.READY || stage == AutoSetupStage.FAILED

    fun toJson(): JSONObject = JSONObject().apply {
        put("gameKey", gameKey); put("gameName", gameName); put("stage", stage.name)
        put("detail", detail); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AutoSetupStatus? {
            val key = json.optString("gameKey").trim()
            val name = json.optString("gameName").trim()
            val stage = runCatching { AutoSetupStage.valueOf(json.optString("stage")) }.getOrNull()
            if (key.isEmpty() || name.isEmpty() || stage == null) return null
            return AutoSetupStatus(key, name, stage, json.optString("detail"), json.optLong("updatedAt", 0L))
        }
    }
}

data class SteamAutoSetupRequest(
    val appId: Int,
    val gameName: String,
    val installDir: String,
    val executablePath: String,
    val coverUrl: String?,
) { val gameKey: String get() = "steam:$appId" }

sealed class AutoSetupResult {
    data class Started(val gameKey: String) : AutoSetupResult()
    data class Failed(val gameKey: String, val message: String) : AutoSetupResult()
}
