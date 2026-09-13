package io.github.p1neapplexpress.openflux.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import io.github.p1neapplexpress.openflux.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opens the MAX web client in a WebView so the user can sign in normally
 * (phone number + SMS code). Once signed in, the web client keeps its session
 * token in localStorage; we poll for it and hand it back to the caller.
 *
 * This is the same token that transport/oneme sends in the websocket login
 * (opcode 19) as `token`, i.e. the value of the --maxToken flag. The numeric
 * account id is captured too when it can be found, because an exit node's own
 * id is exactly what clients pass as --maxUid.
 *
 * Note: MAX exposes no public OAuth flow for third-party apps, so the storage
 * layout is not a documented contract and may change without notice. The
 * scanner below is intentionally generic for that reason, and manual token
 * entry remains available as a fallback.
 */
class MaxLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "max_token"
        const val EXTRA_UID = "max_uid"

        private const val LOGIN_URL = "https://web.max.ru/"
        private const val POLL_INTERVAL_MS = 1500L

        /** Storage keys / JSON fields that plausibly hold an auth token. */
        private val TOKEN_KEY_HINTS = listOf("token", "auth", "session", "credential")

        /** Tokens are long and opaque; this filters out flags, counters, ids. */
        private val TOKEN_REGEX = Regex("^[A-Za-z0-9._:~+/=-]{20,}$")

        /** Storage keys / JSON fields that plausibly hold the account id. */
        private val UID_KEY_HINTS = listOf(
            "userid", "user_id", "uid", "profileid", "profile_id",
            "contactid", "contact_id", "accountid", "account_id",
        )

        /** MAX account ids are plain numbers. */
        private val UID_REGEX = Regex("^\\d{5,20}$")
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    private val pollTask = object : Runnable {
        override fun run() {
            tryExtractToken()
            if (!delivered) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_max_login)

        web = findViewById(R.id.maxWebView)
        progress = findViewById(R.id.maxProgress)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                tryExtractToken()
            }
        }

        web.loadUrl(LOGIN_URL)
        handler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    private fun tryExtractToken() {
        if (delivered) return

        val js = """
            (function () {
              try {
                var out = {};
                for (var i = 0; i < localStorage.length; i++) {
                  var k = localStorage.key(i);
                  out[k] = localStorage.getItem(k);
                }
                for (var j = 0; j < sessionStorage.length; j++) {
                  var sk = sessionStorage.key(j);
                  if (!(sk in out)) out[sk] = sessionStorage.getItem(sk);
                }
                return JSON.stringify(out);
              } catch (e) {
                return "{}";
              }
            })();
        """.trimIndent()

        web.evaluateJavascript(js) { raw ->
            val storage = parseStorage(raw) ?: return@evaluateJavascript
            val token = scan(storage, TOKEN_KEY_HINTS, TOKEN_REGEX)
            // The token is mandatory; the id is best-effort.
            if (token != null) deliver(token, scan(storage, UID_KEY_HINTS, UID_REGEX))
        }
    }

    private fun deliver(token: String, uid: String?) {
        if (delivered) return
        delivered = true
        handler.removeCallbacks(pollTask)

        val data = Intent().putExtra(EXTRA_TOKEN, token)
        if (!uid.isNullOrEmpty()) data.putExtra(EXTRA_UID, uid)

        setResult(Activity.RESULT_OK, data)
        finish()
    }

    /**
     * `evaluateJavascript` hands back a JSON-encoded string, so the payload is
     * double-encoded: unwrap once to get the storage map.
     */
    private fun parseStorage(evalResult: String?): JSONObject? {
        if (evalResult.isNullOrEmpty() || evalResult == "null") return null
        val decoded = runCatching { JSONArray("[$evalResult]").getString(0) }.getOrNull()
            ?: return null
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    /** Walks the storage map, descending into values that are themselves JSON. */
    private fun scan(storage: JSONObject, keyHints: List<String>, regex: Regex): String? {
        val keys = storage.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = storage.optString(key, "").trim()
            if (value.isEmpty()) continue

            if (value.startsWith("{") || value.startsWith("[")) {
                val nested = runCatching {
                    if (value.startsWith("{")) searchJson(JSONObject(value), keyHints, regex)
                    else searchJson(JSONArray(value), keyHints, regex)
                }.getOrNull()
                if (nested != null) return nested
            } else if (matchesHint(key, keyHints) && regex.matches(value)) {
                return value
            }
        }
        return null
    }

    private fun searchJson(node: Any?, keyHints: List<String>, regex: Regex): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (matchesHint(key, keyHints)) {
                        // Ids are often stored as numbers rather than strings.
                        val text = when (value) {
                            is String -> value
                            is Number -> value.toString()
                            else -> null
                        }
                        if (text != null && regex.matches(text)) return text
                    }
                    searchJson(value, keyHints, regex)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    searchJson(node.opt(i), keyHints, regex)?.let { return it }
                }
            }
        }
        return null
    }

    private fun matchesHint(key: String, keyHints: List<String>): Boolean {
        val lower = key.lowercase()
        return keyHints.any { lower.contains(it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        super.onDestroy()
    }
}
