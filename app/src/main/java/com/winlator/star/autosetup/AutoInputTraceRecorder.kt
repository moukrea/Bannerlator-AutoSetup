package com.winlator.star.autosetup

import android.content.Context
import android.os.SystemClock
import com.winlator.star.inputcontrols.GamepadState
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Process-wide recorder attached at the final guest-input injection points. File I/O happens on a
 * dedicated thread so recording does not add storage latency to the input or render threads. */
object AutoInputTraceRecorder {
    data class Result(val path: String, val events: Long, val droppedEvents: Long)

    private data class Session(
        val gameKey: String,
        val startedAt: Long,
        val file: File,
        val queue: ArrayBlockingQueue<String>,
        val events: AtomicLong = AtomicLong(),
        val dropped: AtomicLong = AtomicLong(),
        val writerThread: Thread,
    )

    @Volatile private var session: Session? = null
    private const val STOP = "\u0000"

    @JvmStatic @Synchronized fun start(context: Context, gameKey: String) {
        stop()
        val dir = File(context.filesDir, "autosetup/input-traces").apply { mkdirs() }
        val safe = gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.jsonl")
        val queue = ArrayBlockingQueue<String>(16_384)
        lateinit var created: Session
        val worker = Thread({
            file.bufferedWriter().use { writer ->
                while (true) {
                    val line = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    if (line == STOP) break
                    writer.appendLine(line)
                }
                drain(queue, writer)
            }
        }, "auto-input-trace")
        created = Session(gameKey, SystemClock.elapsedRealtime(), file, queue, writerThread = worker)
        session = created
        worker.start()
    }

    @JvmStatic @Synchronized fun stop(): Result? {
        val current = session ?: return null
        session = null
        runCatching { current.queue.put(STOP) }
        runCatching { current.writerThread.join(2_000) }
        return Result(current.file.absolutePath, current.events.get(), current.dropped.get())
    }

    @JvmStatic fun pointerMove(x: Int, y: Int) = record("pointer_move", x, y)
    @JvmStatic fun pointerDelta(dx: Int, dy: Int) = record("pointer_delta", dx, dy)
    @JvmStatic fun pointerButton(code: Int, pressed: Boolean) = record("pointer_button", code, if (pressed) 1 else 0)
    @JvmStatic fun key(code: Int, keysym: Int, pressed: Boolean) = record("key", code, keysym, if (pressed) 1 else 0)

    @JvmStatic fun gamepad(slot: Int, state: GamepadState) {
        enqueue(JSONObject().apply {
            put("type", "gamepad")
            put("slot", slot)
            put("buttons", state.buttons.toInt())
            put("lx", state.thumbLX.toDouble()); put("ly", state.thumbLY.toDouble())
            put("rx", state.thumbRX.toDouble()); put("ry", state.thumbRY.toDouble())
            put("lt", state.triggerL.toDouble()); put("rt", state.triggerR.toDouble())
            put("dx", state.getDPadX().toInt()); put("dy", state.getDPadY().toInt())
        })
    }

    private fun record(type: String, a: Int, b: Int, c: Int? = null) {
        enqueue(JSONObject().apply {
            put("type", type); put("a", a); put("b", b); c?.let { put("c", it) }
        })
    }

    private fun enqueue(json: JSONObject) {
        val current = session ?: return
        json.put("t", SystemClock.elapsedRealtime() - current.startedAt)
        if (current.queue.offer(json.toString())) current.events.incrementAndGet()
        else current.dropped.incrementAndGet()
    }

    private fun drain(queue: ArrayBlockingQueue<String>, writer: BufferedWriter) {
        while (true) {
            val line = queue.poll() ?: break
            if (line != STOP) writer.appendLine(line)
        }
    }
}
