package com.winlator.star.autosetup

import android.content.Context
import com.winlator.star.R

object AutoSetupRuntimeSignals {
    const val EXTRA_GAME_KEY = "auto_setup_game_key"

    @JvmStatic fun onFirstApplicationFrame(context: Context, gameKey: String?) {
        if (gameKey.isNullOrBlank()) return
        val journal = AutoSetupJournal(context); val current = journal.read(gameKey) ?: return
        if (current.stage == AutoSetupStage.VALIDATING) journal.transition(current, AutoSetupStage.READY, context.getString(R.string.auto_setup_first_frame))
    }

    @JvmStatic fun onSessionEnded(context: Context, gameKey: String?) {
        if (gameKey.isNullOrBlank()) return
        val journal = AutoSetupJournal(context); val current = journal.read(gameKey) ?: return
        if (current.stage == AutoSetupStage.LAUNCHING || current.stage == AutoSetupStage.VALIDATING)
            journal.transition(current, AutoSetupStage.FAILED, context.getString(R.string.auto_setup_early_exit))
    }
}
