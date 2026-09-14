package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.p1neapplexpress.openflux.data.AdminRepository
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class NativeProcessSupervisor(private val context: Context) {

    companion object {
        private const val TAG = "NativeProcSupervisor"
        private const val STARTUP_GRACE_MS = 2_000L
        private const val NATIVE_LIB = "libp1npplydtransport.so"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var stdoutThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)

    // Verbose transport logging is a throughput killer: --debug makes the native
    // core emit per-packet lines, each of which we then dispatch to the log bus
    // and Logcat. Only enable it when the user explicitly turned on debug.
    private val debug: Boolean by lazy {
        runCatching { AdminRepository(context).loadNode().debug }.getOrDefault(false)
    }

    val isConnected: Boolean get() = connected.get()
    val isRunning: Boolean get() = running.get()

    fun start(transportType: String, payload: List<String>) {
        if (running.getAndSet(true)) {
            Logx.d(TAG, "already running, ignoring start")
            return
        }
        shuttingDown.set(false)
        Logx.i(TAG, "start transport=$transportType")
        spawn(transportType, payload)
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        running.set(false)
        connected.set(false)
        cleanup()
    }

    private fun spawn(transportType: String, payload: List<String>) {
        val libPath = "${context.applicationInfo.nativeLibraryDir}/$NATIVE_LIB"
        try {
            val cmd = if (debug) listOf(libPath, "--debug") + payload else listOf(libPath) + payload
            Logx.i(TAG, "exec: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            process = pb.start()

            stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            if (debug) android.util.Log.d("NativeStdout", l)
                            EventBus.dispatch(AppEvent.LogMessage(l))
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "NativeStdoutReader"
                isDaemon = true
                start()
            }

            
            handler.postDelayed({
                if (!shuttingDown.get()) {
                    connected.set(true)
                    EventBus.dispatch(AppEvent.TransportConnected)
                    Logx.i(TAG, "native process up")
                }
            }, STARTUP_GRACE_MS)

        } catch (e: Exception) {
            Logx.e(TAG, "spawn failed", e)
        }
    }

    private fun cleanup() {
        stdoutThread?.interrupt()
        stdoutThread = null
        process?.let { p ->
            if (p.isAlive) p.destroy()
            handler.postDelayed({ if (p.isAlive) p.destroyForcibly() }, 1000L)
        }
        process = null
    }

}
