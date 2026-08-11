package com.winlator.star.autosetup

import android.content.Context
import android.os.SystemClock
import com.winlator.star.R
import java.util.concurrent.ConcurrentHashMap

object AutoSetupRuntimeSignals {
    const val EXTRA_GAME_KEY = "auto_setup_game_key"
    const val EXTRA_RECORD_BENCHMARK = "auto_setup_record_benchmark"

    private data class Probe(
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var firstApplicationAt: Long = 0,
        var frames: Long = 0,
        val recordBenchmark: Boolean = false,
    )

    private val probes = ConcurrentHashMap<String, Probe>()

    @JvmStatic fun onSessionStarted(context: Context, gameKey: String?, recordBenchmark: Boolean) {
        if (gameKey.isNullOrBlank()) return
        probes[gameKey] = Probe(recordBenchmark = recordBenchmark)
    }

    @JvmStatic fun onApplicationWindow(context: Context, gameKey: String?, title: String?, className: String?) {
        if (gameKey.isNullOrBlank()) return
        val text = "${title.orEmpty()} ${className.orEmpty()}".lowercase()
        if (text.contains("application load error") || text.contains("steam must be running") || text.contains("failed to find steam")) {
            val journal = AutoSetupJournal(context)
            val current = journal.read(gameKey) ?: return
            if (current.stage == AutoSetupStage.VALIDATING) journal.transition(current, AutoSetupStage.FAILED, context.getString(R.string.auto_setup_steam_client_required))
            return
        }
        probes[gameKey]?.let { if (it.firstApplicationAt == 0L) it.firstApplicationAt = SystemClock.elapsedRealtime() }
    }

    @JvmStatic fun onFramePresented(context: Context, gameKey: String?) {
        if (gameKey.isNullOrBlank()) return
        val probe = probes[gameKey] ?: return
        if (probe.firstApplicationAt == 0L) return
        probe.frames++
        if (SystemClock.elapsedRealtime() - probe.firstApplicationAt < VALIDATION_MS || probe.frames < MIN_PRESENTED_FRAMES) return
        val journal = AutoSetupJournal(context)
        val current = journal.read(gameKey) ?: return
        if (current.stage == AutoSetupStage.VALIDATING) journal.transition(current, AutoSetupStage.READY, context.getString(R.string.auto_setup_gameplay_validated))
    }

    @JvmStatic fun onSessionEnded(context: Context, gameKey: String?) {
        if (gameKey.isNullOrBlank()) return
        val probe = probes.remove(gameKey)
        val journal = AutoSetupJournal(context); val current = journal.read(gameKey) ?: return
        if (current.stage == AutoSetupStage.LAUNCHING || current.stage == AutoSetupStage.VALIDATING)
            journal.transition(current, AutoSetupStage.FAILED, context.getString(R.string.auto_setup_early_exit))
        if (probe?.recordBenchmark == true) {
            val duration = (SystemClock.elapsedRealtime() - probe.startedAt).coerceAtLeast(1L)
            AutoBenchmarkStore(context).write(AutoBenchmarkSummary(
                gameKey = gameKey,
                durationMs = duration,
                presentedFrames = probe.frames,
                averageFps = probe.frames * 1000f / duration,
                stable = current.stage == AutoSetupStage.READY,
                recordedAt = System.currentTimeMillis(),
            ))
        }
    }

    private const val VALIDATION_MS = 12_000L
    private const val MIN_PRESENTED_FRAMES = 120L
}
