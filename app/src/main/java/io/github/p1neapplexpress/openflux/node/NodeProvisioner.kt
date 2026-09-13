package io.github.p1neapplexpress.openflux.node

import android.util.Base64
import io.github.p1neapplexpress.openflux.data.NodeConfig

/**
 * Turns the user's VPS into an OpenFlux exit node over SSH: installs Go,
 * builds `universal-bypass-tool` from source and runs it under systemd as
 * `openflux-node`.
 *
 * Every remote script is shipped base64-encoded and piped into `bash -s`, so
 * tokens and multi-line here-docs never have to survive shell quoting.
 */
class NodeProvisioner(private val cfg: NodeConfig) {

    private val ssh = SshRunner(cfg)

    /** Elevate unless we already log in as root. */
    private val sudo: String = if (cfg.user.trim() == "root") "" else "sudo -n "

    fun install(onLine: (String) -> Unit): ExecResult {
        onLine("[app] connecting to ${cfg.host}")
        val arch = detectArch(onLine)
        onLine("[app] remote architecture: $arch")
        return run(installScript(arch), onLine)
    }

    fun status(onLine: (String) -> Unit): ExecResult = run(
        "${sudo}systemctl is-active $SERVICE || true\n" +
            "${sudo}systemctl status $SERVICE --no-pager -n 20 || true\n",
        onLine,
    )

    fun logs(onLine: (String) -> Unit): ExecResult =
        run("${sudo}journalctl -u $SERVICE -n 120 --no-pager || true\n", onLine)

    fun stop(onLine: (String) -> Unit): ExecResult = run(
        "${sudo}systemctl stop $SERVICE || true\n${sudo}systemctl is-active $SERVICE || true\n",
        onLine,
    )

    fun restart(onLine: (String) -> Unit): ExecResult = run(
        "${sudo}systemctl restart $SERVICE\nsleep 2\n${sudo}systemctl is-active $SERVICE || true\n",
        onLine,
    )

    /** Resolved on the host so the install script needs no shell variables. */
    private fun detectArch(onLine: (String) -> Unit): String {
        val raw = ssh.exec("uname -m").output.trim().lines().lastOrNull()?.trim().orEmpty()
        return when (raw) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> {
                onLine("[app] unrecognised architecture '$raw', assuming amd64")
                "amd64"
            }
        }
    }

    private fun run(script: String, onLine: (String) -> Unit): ExecResult {
        val payload = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        return ssh.exec("printf %s '$payload' | base64 -d | bash -s", onLine)
    }

    private fun installScript(arch: String): String {
        val debugFlag = if (cfg.debug) " --debug" else ""
        val token = cfg.maxToken.trim()
        val repo = cfg.repoUrl.ifBlank { NodeConfig.DEFAULT_REPO }

        return """
            set -eu

            echo "[openflux] preparing /opt/openflux"
            ${sudo}mkdir -p /opt/openflux

            echo "[openflux] installing build prerequisites"
            if command -v apt-get >/dev/null 2>&1; then
              ${sudo}apt-get update -y
              ${sudo}env DEBIAN_FRONTEND=noninteractive apt-get install -y git curl tar ca-certificates
            elif command -v dnf >/dev/null 2>&1; then
              ${sudo}dnf install -y git curl tar ca-certificates
            elif command -v yum >/dev/null 2>&1; then
              ${sudo}yum install -y git curl tar ca-certificates
            else
              echo "[openflux] unknown package manager, assuming git/curl/tar are present"
            fi

            if /usr/local/go/bin/go version >/dev/null 2>&1; then
              echo "[openflux] go already present:"
              /usr/local/go/bin/go version
            else
              echo "[openflux] installing go $GO_VERSION ($arch)"
              curl -fsSL https://go.dev/dl/go$GO_VERSION.linux-$arch.tar.gz -o /tmp/go.tgz
              ${sudo}rm -rf /usr/local/go
              ${sudo}tar -C /usr/local -xzf /tmp/go.tgz
              rm -f /tmp/go.tgz
            fi

            echo "[openflux] fetching sources"
            if [ -d /opt/openflux/src/.git ]; then
              ${sudo}git -C /opt/openflux/src fetch --depth 1 origin HEAD
              ${sudo}git -C /opt/openflux/src reset --hard FETCH_HEAD
            else
              ${sudo}rm -rf /opt/openflux/src
              ${sudo}git clone --depth 1 $repo /opt/openflux/src
            fi

            echo "[openflux] building, this takes a few minutes"
            cd /opt/openflux/src
            ${sudo}env PATH=/usr/local/go/bin:/usr/bin:/bin HOME=/root GOFLAGS=-buildvcs=false \
              go build -o /opt/openflux/universal-bypass-tool .

            echo "[openflux] writing credentials"
            ${sudo}tee /etc/openflux-node.env >/dev/null <<'ENVEOF'
            MAX_TOKEN=$token
            ENVEOF
            ${sudo}chmod 600 /etc/openflux-node.env

            echo "[openflux] writing systemd unit"
            ${sudo}tee /etc/systemd/system/$SERVICE.service >/dev/null <<'UNITEOF'
            [Unit]
            Description=OpenFlux exit node
            After=network-online.target
            Wants=network-online.target

            [Service]
            Type=simple
            EnvironmentFile=/etc/openflux-node.env
            ExecStart=/opt/openflux/universal-bypass-tool --exit-node --transport oneme --maxToken ${'$'}{MAX_TOKEN}$debugFlag
            Restart=always
            RestartSec=5
            LimitNOFILE=65535

            [Install]
            WantedBy=multi-user.target
            UNITEOF

            ${sudo}systemctl daemon-reload
            ${sudo}systemctl enable $SERVICE >/dev/null 2>&1 || true
            ${sudo}systemctl restart $SERVICE
            sleep 3
            echo "[openflux] service state:"
            ${sudo}systemctl is-active $SERVICE || true
            ${sudo}journalctl -u $SERVICE -n 25 --no-pager || true
            echo "[openflux] done"
        """.trimIndent()
    }

    private companion object {
        const val SERVICE = "openflux-node"

        /** OpenFlux go.mod requires go 1.26.4. */
        const val GO_VERSION = "1.26.4"
    }
}
