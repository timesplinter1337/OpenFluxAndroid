package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.Serializable

/**
 * SSH + MAX credentials for the user's own exit node (a Linux VPS).
 *
 * The node runs `universal-bypass-tool --exit-node --transport oneme`, so it
 * needs its own MAX account token. That account's numeric user id is exactly
 * what a client has to pass as `--maxUid`, which is why [maxUid] is stored
 * here and reused to prefill new client tunnels.
 */
@Serializable
data class NodeConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    /** When true authenticate with [privateKey]; otherwise with [password]. */
    val useKeyAuth: Boolean = false,
    val password: String = "",
    val privateKey: String = "",
    /** MAX token of the account the node itself logs in with. */
    val maxToken: String = "",
    /** Numeric MAX user id of the node account. Clients dial this id. */
    val maxUid: String = "",
    val debug: Boolean = false,
    val repoUrl: String = DEFAULT_REPO,
) {
    companion object {
        const val DEFAULT_REPO = "https://github.com/p1neappleXpress/OpenFlux.git"
    }
}
