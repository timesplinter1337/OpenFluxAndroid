package io.github.p1neapplexpress.openflux.node

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.p1neapplexpress.openflux.data.NodeConfig
import java.util.Properties

data class ExecResult(val exitCode: Int, val output: String)

/**
 * Minimal blocking SSH exec helper. Must be called from a background thread.
 *
 * stderr is folded into stdout (`2>&1`) so that a single reader can never
 * deadlock on a full stderr pipe.
 */
class SshRunner(private val cfg: NodeConfig) {

    fun exec(command: String, onLine: (String) -> Unit = {}): ExecResult {
        val session = connect()
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("$command 2>&1")
            channel.setPty(false)
            val input = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)

            val collected = StringBuilder()
            input.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    collected.append(line).append('\n')
                    onLine(line)
                }
            }

            // The stream closes before the channel reports the exit status.
            var waited = 0L
            while (!channel.isClosed && waited < EXIT_WAIT_MS) {
                Thread.sleep(POLL_MS)
                waited += POLL_MS
            }
            val code = channel.exitStatus
            channel.disconnect()
            return ExecResult(code, collected.toString())
        } finally {
            session.disconnect()
        }
    }

    private fun connect(): Session {
        val jsch = JSch()
        if (cfg.useKeyAuth && cfg.privateKey.isNotBlank()) {
            jsch.addIdentity("openflux-node", cfg.privateKey.trim().toByteArray(), null, null)
        }

        val session = jsch.getSession(
            cfg.user.ifBlank { "root" },
            cfg.host.trim(),
            if (cfg.port > 0) cfg.port else 22,
        )
        if (!cfg.useKeyAuth) session.setPassword(cfg.password)

        session.setConfig(Properties().apply {
            // The app keeps no known_hosts store and the node is the user's own
            // VPS, so the host key is accepted on first use.
            put("StrictHostKeyChecking", "no")
            put(
                "PreferredAuthentications",
                if (cfg.useKeyAuth) "publickey" else "password,keyboard-interactive",
            )
        })

        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val EXIT_WAIT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
