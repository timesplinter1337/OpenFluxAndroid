package io.github.p1neapplexpress.openflux.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json

/**
 * Stores the admin-mode flag and the exit-node SSH configuration.
 *
 * Kept in its own prefs file so it is independent of the tunnel list.
 */
class AdminRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var adminMode: Boolean
        get() = prefs.getBoolean(KEY_ADMIN, false)
        set(value) = prefs.edit { putBoolean(KEY_ADMIN, value) }

    fun loadNode(): NodeConfig {
        val raw = prefs.getString(KEY_NODE, null) ?: return NodeConfig()
        return runCatching { json.decodeFromString(NodeConfig.serializer(), raw) }
            .getOrElse { NodeConfig() }
    }

    fun saveNode(config: NodeConfig) {
        prefs.edit { putString(KEY_NODE, json.encodeToString(NodeConfig.serializer(), config)) }
    }

    private companion object {
        const val PREF = "openflux_admin"
        const val KEY_ADMIN = "admin_mode"
        const val KEY_NODE = "node_config"
    }
}
