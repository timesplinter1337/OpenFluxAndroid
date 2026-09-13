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
 * (opcode 19) as `token`, i.e. the value of the --maxToken flag.
 *
 * Note: MAX exposes no public OAuth flow for third-party apps, so the storage
 * layout is not a documented contract and may change without notice. The
 * scanner below is intentionally generic for that reason, and manual token
 * entry remains available as a fallback.
 */
class MaxLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "max_token"

        private const val LOGIN_URL = "https://web.max.ru/"
        private const val POLL_INTERVAL_MS = 1500L

        /** Storage keys / JSON fields that plausibly hold an auth token. */
        private val TOKEN_KEY_HINTS = listOf("token", "auth", "session", "credential")

        /** Tokens are long and opaque; this filters out flags, counters, ids. */
        private val TOKEN_REGEX = Regex("^[A-Za-z0-9._:~+/=-]{20,}$")
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
            val token = findToken(raw)
            if (token != null) deliver(token)
        }
    }

    private fun deliver(token: String) {
        if (delivered) return
        delivered = true
        handler.removeCallbacks(pollTask)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    /**
     * `evaluateJavascript` hands back a JSON-encoded string, so the payload is
     * double-encoded: unwrap once, then walk the storage map.
     */
    private fun findToken(evalResult: String?): String? {
        if (evalResult.isNullOrEmpty() || evalResult == "null") return null

        val decoded = runCatching { JSONArray("[$evalResult]").getString(0) }.getOrNull() ?: return null
        val storage = runCatching { JSONObject(decoded) }.getOrNull() ?: return null

        val keys = storage.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = storage.optString(key, "").trim()
            if (value.isEmpty()) continue

            if (value.startsWith("{") || value.startsWith("[")) {
                val nested = runCatching {
                    if (value.startsWith("{")) searchJson(JSONObject(value))
                    else searchJson(JSONArray(value))
                }.getOrNull()
                if (nested != null) return nested
            } else if (looksLikeTokenKey(key) && TOKEN_REGEX.matches(value)) {
                return value
            }
        }
        return null
    }

    private fun searchJson(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (value is String && looksLikeTokenKey(key) && TOKEN_REGEX.matches(value)) {
                        return value
                    }
                    searchJson(value)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    searchJson(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun looksLikeTokenKey(key: String): Boolean {
        val lower = key.lowercase()
        return TOKEN_KEY_HINTS.any { lower.contains(it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTask)
        super.onDestroy()
    }
}
