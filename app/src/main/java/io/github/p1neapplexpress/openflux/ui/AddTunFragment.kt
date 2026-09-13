package io.github.p1neapplexpress.openflux.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.dpToPx
import kotlinx.serialization.json.Json
import kotlin.random.Random

class AddTunFragment : BaseFragment() {

    companion object {
        private const val ARG_EDIT_JSON = "edit_json"
        private const val ARG_PREFILL_UID = "prefill_uid"
        private const val ARG_PREFILL_NAME = "prefill_name"

        fun new() = AddTunFragment()

        fun edit(tunnel: Tunnel): AddTunFragment = AddTunFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EDIT_JSON, Json.encodeToString(Tunnel.serializer(), tunnel))
            }
        }

        /**
         * New MAX tunnel pointing at an exit node the user just provisioned,
         * with the node's account id filled in as --maxUid. The token still has
         * to be the phone's own MAX account, not the node's.
         */
        fun forNode(uid: String, name: String): AddTunFragment = AddTunFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PREFILL_UID, uid)
                putString(ARG_PREFILL_NAME, name)
            }
        }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private var transport = TransportType.yandex
    private var debug = false
    private var editing: Tunnel? = null

    /** Token field, kept around so the MAX sign-in result can fill it in. */
    private var maxTokenField: TextView? = null

    private val maxLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val token = result.data?.getStringExtra(MaxLoginActivity.EXTRA_TOKEN)
            if (result.resultCode == Activity.RESULT_OK && !token.isNullOrEmpty()) {
                maxTokenField?.text = token
                Toast.makeText(requireContext(), R.string.max_login_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.max_login_failed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = arguments?.getString(ARG_EDIT_JSON)
        if (raw != null) {
            editing = runCatching { Json.decodeFromString(Tunnel.serializer(), raw) }.getOrNull()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_add_tun, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val transportLayout = view.findViewById<View>(R.id.select_transport_layout)
        val maxContainer = view.findViewById<View>(R.id.maxContainer)
        val yandexContainer = view.findViewById<View>(R.id.yandexUrlContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val debugLabel = view.findViewById<TextView>(R.id.selectedDebug)
        val debugSwitch = view.findViewById<SwitchMaterial>(R.id.debugSwitch)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val maxLogin = view.findViewById<Button>(R.id.loginMaxButton)
        val name = view.findViewById<TextView>(R.id.name)
        val save = view.findViewById<Button>(R.id.saveButton)

        maxTokenField = maxToken

        maxLogin.setOnClickListener {
            maxLoginLauncher.launch(Intent(requireContext(), MaxLoginActivity::class.java))
        }

        // ─── Заполнение при редактировании ───
        editing?.let { t ->
            name.setText(t.name)
            transport = TransportType.from(t.transportType)

            when (transport) {
                TransportType.yandex -> {
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                }
                TransportType.vyandex -> {
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.vyandex_backend)
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                }
                TransportType.max -> {
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    maxToken.setText(argValue(t.transportConnPayload, "--maxToken"))
                    maxUid.setText(argValue(t.transportConnPayload, "--maxUid"))
                }
            }

            debug = t.transportConnPayload.contains("--debug")
            debugSwitch.isChecked = debug
            debugLabel.text = getString(if (debug) R.string.on else R.string.off)
            save.text = getString(R.string.action_edit)
        }

        // ─── Заполнение при создании туннеля к своей ноде ───
        val prefillUid = arguments?.getString(ARG_PREFILL_UID)
        if (editing == null && !prefillUid.isNullOrBlank()) {
            transport = TransportType.max
            maxContainer.isVisible = true
            yandexContainer.isVisible = false
            transportLabel.text = getString(R.string.max_messenger_backend)
            maxUid.setText(prefillUid)
            arguments?.getString(ARG_PREFILL_NAME)?.let { if (it.isNotBlank()) name.setText(it) }
        }

        transportLayout.setOnClickListener {
            it.showTransportDropdown(
                onYandex = {
                    transport = TransportType.yandex
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                },
                onVyandex = {
                    transport = TransportType.vyandex
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.vyandex_backend)
                },
                onMax = {
                    transport = TransportType.max
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    transportLabel.text = getString(R.string.max_messenger_backend)
                },
            )
        }

        debugSwitch.setOnCheckedChangeListener { _, checked ->
            debug = checked
            debugLabel.text = getString(if (checked) R.string.on else R.string.off)
        }

        save.setOnClickListener {
            val n = name.text.trim().toString()
            if (n.isEmpty()) {
                Toast.makeText(requireContext(), R.string.name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newTunnel = createTunnel(
                id = editing?.id ?: Random(System.currentTimeMillis()).nextLong(),
                name = n,
                docUrl = docUrl.text.trim().toString(),
                maxToken = maxToken.text.trim().toString(),
                maxUid = maxUid.text.trim().toString(),
            ) ?: return@setOnClickListener

            val old = editing
            if (old != null) {
                vm.updateTunnel(old, newTunnel)
                Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
            } else {
                vm.addTunnel(newTunnel)
            }

            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        if (editing == null) {
            if (prefillUid.isNullOrBlank()) {
                transportLabel.text = getString(R.string.yandex_docs_backend)
            }
            debugLabel.text = getString(R.string.off)
        }
    }

    override fun onDestroyView() {
        maxTokenField = null
        super.onDestroyView()
    }

    private fun argValue(payload: List<String>, key: String): String {
        val idx = payload.indexOf(key)
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else ""
    }

    private fun createTunnel(
        id: Long,
        name: String,
        docUrl: String,
        maxToken: String,
        maxUid: String,
    ): Tunnel? {
        val payload = when (transport) {
            TransportType.yandex -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("yandex")
                    add("--url"); add(docUrl)
                    if (debug) add("--debug")
                }
            }
            TransportType.vyandex -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("vyandex")
                    add("--url"); add(docUrl)
                    if (debug) add("--debug")
                }
            }
            TransportType.max -> {
                if (maxToken.isEmpty() || maxUid.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("oneme")
                    add("--maxToken"); add(maxToken)
                    add("--maxUid"); add(maxUid)
                    if (debug) add("--debug")
                }
            }
        }
        return Tunnel(
            id = id,
            name = name,
            transportType = transport.name,
            transportConnPayload = payload,
        )
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    private fun View.showTransportDropdown(
        onYandex: () -> Unit,
        onVyandex: () -> Unit,
        onMax: () -> Unit,
    ) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.dropdown_transport_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dropdown_transports))
            elevation = 8.dpToPx(context).toFloat()
            animationStyle = R.style.DropdownAnimation
            isOutsideTouchable = true
            isFocusable = true
        }

        popupView.findViewById<View>(R.id.option_yandex)?.setOnClickListener {
            onYandex(); popup.dismiss()
        }
        popupView.findViewById<View>(R.id.option_vyandex)?.setOnClickListener {
            onVyandex(); popup.dismiss()
        }
        popupView.findViewById<View>(R.id.option_max)?.setOnClickListener {
            onMax(); popup.dismiss()
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(this, 0, -popupView.measuredHeight - height - 8.dpToPx(context))
    }
}
