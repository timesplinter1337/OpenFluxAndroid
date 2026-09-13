package io.github.p1neapplexpress.openflux.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.AdminRepository
import io.github.p1neapplexpress.openflux.data.NodeConfig
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.node.NodeProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen. Enabling administrator mode reveals the exit-node section,
 * which provisions the user's own VPS over SSH so that tunnel traffic leaves
 * through it.
 */
class SettingsFragment : BaseFragment() {

    companion object {
        fun new() = SettingsFragment()

        private const val LOG_LIMIT = 20_000
    }

    private lateinit var repo: AdminRepository

    private var nodeCard: View? = null
    private var host: EditText? = null
    private var port: EditText? = null
    private var user: EditText? = null
    private var password: EditText? = null
    private var privateKey: EditText? = null
    private var token: EditText? = null
    private var uid: EditText? = null
    private var useKeySwitch: SwitchMaterial? = null
    private var debugSwitch: SwitchMaterial? = null
    private var log: TextView? = null

    /** Guards against launching two SSH sessions at once. */
    private var busy = false

    private val nodeLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val capturedToken = result.data?.getStringExtra(MaxLoginActivity.EXTRA_TOKEN)
            val capturedUid = result.data?.getStringExtra(MaxLoginActivity.EXTRA_UID)

            if (result.resultCode == Activity.RESULT_OK && !capturedToken.isNullOrEmpty()) {
                token?.setText(capturedToken)
                if (!capturedUid.isNullOrEmpty()) uid?.setText(capturedUid)
                persist()
                toast(R.string.max_login_success)
                if (capturedUid.isNullOrEmpty()) appendLog(getString(R.string.node_uid_missing))
            } else {
                toast(R.string.max_login_failed)
            }
        }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = AdminRepository(requireContext())

        val adminSwitch = view.findViewById<SwitchMaterial>(R.id.adminSwitch)
        nodeCard = view.findViewById(R.id.nodeCard)
        host = view.findViewById(R.id.sshHost)
        port = view.findViewById(R.id.sshPort)
        user = view.findViewById(R.id.sshUser)
        password = view.findViewById(R.id.sshPassword)
        privateKey = view.findViewById(R.id.sshKey)
        token = view.findViewById(R.id.nodeToken)
        uid = view.findViewById(R.id.nodeUid)
        useKeySwitch = view.findViewById(R.id.useKeySwitch)
        debugSwitch = view.findViewById(R.id.nodeDebugSwitch)
        log = view.findViewById(R.id.nodeLog)

        val stored = repo.loadNode()
        host?.setText(stored.host)
        port?.setText(stored.port.toString())
        user?.setText(stored.user)
        password?.setText(stored.password)
        privateKey?.setText(stored.privateKey)
        token?.setText(stored.maxToken)
        uid?.setText(stored.maxUid)
        useKeySwitch?.isChecked = stored.useKeyAuth
        debugSwitch?.isChecked = stored.debug

        adminSwitch.isChecked = repo.adminMode
        nodeCard?.isVisible = repo.adminMode
        adminSwitch.setOnCheckedChangeListener { _, checked ->
            repo.adminMode = checked
            nodeCard?.isVisible = checked
        }

        applyAuthMode(stored.useKeyAuth)
        useKeySwitch?.setOnCheckedChangeListener { _, checked -> applyAuthMode(checked) }

        view.findViewById<MaterialButton>(R.id.nodeLoginButton).setOnClickListener {
            nodeLoginLauncher.launch(Intent(requireContext(), MaxLoginActivity::class.java))
        }

        view.findViewById<MaterialButton>(R.id.saveNodeButton).setOnClickListener {
            persist()
            toast(R.string.node_saved)
        }

        view.findViewById<MaterialButton>(R.id.installNodeButton).setOnClickListener {
            val cfg = collect()
            if (cfg.maxToken.isBlank()) {
                toast(R.string.node_token_required)
                return@setOnClickListener
            }
            runAction("install") { provisioner, onLine -> provisioner.install(onLine) }
        }
        view.findViewById<MaterialButton>(R.id.statusNodeButton).setOnClickListener {
            runAction("status") { provisioner, onLine -> provisioner.status(onLine) }
        }
        view.findViewById<MaterialButton>(R.id.logsNodeButton).setOnClickListener {
            runAction("logs") { provisioner, onLine -> provisioner.logs(onLine) }
        }
        view.findViewById<MaterialButton>(R.id.restartNodeButton).setOnClickListener {
            runAction("restart") { provisioner, onLine -> provisioner.restart(onLine) }
        }
        view.findViewById<MaterialButton>(R.id.stopNodeButton).setOnClickListener {
            runAction("stop") { provisioner, onLine -> provisioner.stop(onLine) }
        }

        view.findViewById<MaterialButton>(R.id.createTunnelButton).setOnClickListener {
            val cfg = collect()
            if (cfg.maxUid.isBlank()) {
                toast(R.string.node_uid_missing)
                return@setOnClickListener
            }
            persist()
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.main,
                    AddTunFragment.forNode(cfg.maxUid, cfg.host.ifBlank { "node" }),
                )
                .addToBackStack("node-tunnel")
                .commit()
        }
    }

    private fun applyAuthMode(useKey: Boolean) {
        privateKey?.isVisible = useKey
        password?.isVisible = !useKey
    }

    private fun collect() = NodeConfig(
        host = host?.text?.toString()?.trim().orEmpty(),
        port = port?.text?.toString()?.trim()?.toIntOrNull() ?: 22,
        user = user?.text?.toString()?.trim()?.ifBlank { "root" } ?: "root",
        useKeyAuth = useKeySwitch?.isChecked == true,
        password = password?.text?.toString().orEmpty(),
        privateKey = privateKey?.text?.toString().orEmpty(),
        maxToken = token?.text?.toString()?.trim().orEmpty(),
        maxUid = uid?.text?.toString()?.trim().orEmpty(),
        debug = debugSwitch?.isChecked == true,
    )

    private fun persist() = repo.saveNode(collect())

    private fun runAction(
        label: String,
        action: (NodeProvisioner, (String) -> Unit) -> Unit,
    ) {
        if (busy) {
            toast(R.string.node_busy)
            return
        }
        val cfg = collect()
        if (cfg.host.isBlank()) {
            toast(R.string.node_host_required)
            return
        }

        repo.saveNode(cfg)
        busy = true
        appendLog("")
        appendLog("=== $label ===")

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { action(NodeProvisioner(cfg)) { line -> appendLog(line) } }
                    .onFailure { appendLog("!! ${it.javaClass.simpleName}: ${it.message}") }
            }
            busy = false
        }
    }

    /** Safe to call from any thread. */
    private fun appendLog(line: String) {
        val view = log ?: return
        view.post {
            val merged = view.text.toString() + line + "\n"
            view.text = if (merged.length > LOG_LIMIT) merged.takeLast(LOG_LIMIT) else merged
        }
    }

    private fun toast(resId: Int) =
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        nodeCard = null
        host = null
        port = null
        user = null
        password = null
        privateKey = null
        token = null
        uid = null
        useKeySwitch = null
        debugSwitch = null
        log = null
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
