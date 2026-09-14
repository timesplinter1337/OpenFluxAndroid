package io.github.p1neapplexpress.openflux.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.Logx
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
 * Capturing the account's own numeric id (what clients dial as --maxUid) is
 * trickier: the web client does NOT keep it in localStorage in any dependable
 * place — it lives in IndexedDB — so scraping storage for it is unreliable and
 * previously failed outright. Instead, once we have the token we speak the real
 * MAX protocol from inside the page: open the same websocket the web client
 * uses (wss://ws-api.oneme.ru/websocket), perform the opcode 6 handshake and
 * the opcode 19 login-by-token, and read the signed-in account's own profile id
 * straight from the SYNC response. Doing this in the page context means MAX's
 * own CSP already whitelists the websocket host.
 *
 * Each launch starts from a cleared session (see onCreate). MAX otherwise
 * persists its cookies + IndexedDB in the shared WebView and silently resumes
 * whichever account signed in last, which prevented switching between the node
 * account and the calling account.
 *
 * Note: MAX exposes no public OAuth flow for third-party apps, so none of this
 * is a documented contract and may change without notice. Manual token/id entry
 * remains available as a fallback, and a token-free diagnostic is reported when
 * the id cannot be resolved automatically.
 */
class MaxLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "max_token"
        const val EXTRA_UID = "max_uid"
        const val EXTRA_DEBUG = "max_debug"

        private const val LOGIN_URL = "https://web.max.ru/"
        private const val POLL_INTERVAL_MS = 1500L

        /** How long to keep trying the websocket id lookup before giving up. */
        private const val UID_GRACE_MS = 15_000L

        /** Storage keys / JSON fields that plausibly hold an auth token. */
        private val TOKEN_KEY_HINTS = listOf("token", "auth", "session", "credential")

        /** Tokens are long and opaque; this filters out flags, counters, ids. */
        private val TOKEN_REGEX = Regex("^[A-Za-z0-9._:~+/=-]{20,}$")

        /** Storage keys / JSON fields that plausibly hold the account id. */
        private val UID_KEY_HINTS = listOf(
            "userid", "user_id", "uid", "profileid", "profile_id",
            "contactid", "contact_id", "accountid", "account_id",
        )

        /**
         * Containers whose own id field almost certainly identifies the
         * signed-in account. Used only by the storage-scan fallback.
         */
        private val SELF_CONTAINER_HINTS = listOf(
            "profile", "account", "self", "me", "owner",
            "currentuser", "current_user", "user",
        )

        /** Bare id-like keys, accepted only inside a self/profile container. */
        private val BARE_ID_KEYS = listOf("id", "_id", "userid", "user_id", "uid")

        /** MAX account ids are plain numbers. */
        private val UID_REGEX = Regex("^\\d{5,20}$")

        /** Snapshots localStorage + sessionStorage as a flat JSON string. */
        private val STORAGE_JS = """
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

        /** Reads back whatever the websocket id lookup has produced so far. */
        private val UID_READER_JS = """
            (function () {
              try {
                return JSON.stringify({
                  uid: window.__ofUid || "",
                  state: window.__ofState || "",
                  dump: window.__ofDump || ""
                });
              } catch (e) { return "{}"; }
            })();
        """.trimIndent()
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    /** Set once the token is found and the websocket id lookup is kicked off. */
    private var uidFetchStarted = false
    private var tokenFoundAt = 0L
    private var lastDebug = ""

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

        // Start every login from a clean, signed-out session so the user can
        // switch between the node account and the calling account. MAX otherwise
        // reuses its persisted cookies/IndexedDB in the shared WebView and
        // silently resumes whichever account signed in last. The token we need
        // is already persisted by the caller, so discarding the web session is
        // safe.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
        web.clearFormData()
        web.clearHistory()

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

        web.evaluateJavascript(STORAGE_JS) { raw ->
            val storage = parseStorage(raw) ?: return@evaluateJavascript
            val token = scan(storage, TOKEN_KEY_HINTS, TOKEN_REGEX) ?: return@evaluateJavascript

            // The token is here; kick off the protocol-level id lookup once.
            if (!uidFetchStarted) {
                uidFetchStarted = true
                tokenFoundAt = System.currentTimeMillis()
                web.evaluateJavascript(uidFetchJs(token), null)
            }

            // Poll the websocket lookup result.
            web.evaluateJavascript(UID_READER_JS) { rawRes ->
                val res = parseStorage(rawRes)
                val wsUid = res?.optString("uid").orEmpty()
                val state = res?.optString("state").orEmpty()
                val dump = res?.optString("dump").orEmpty()
                if (dump.isNotEmpty()) lastDebug = dump

                if (UID_REGEX.matches(wsUid)) {
                    deliver(token, wsUid, "uid via MAX sync ($state)")
                    return@evaluateJavascript
                }

                // Give the websocket a bounded amount of time, then fall back to
                // the storage scan and report why the protocol path did not win.
                if (System.currentTimeMillis() - tokenFoundAt >= UID_GRACE_MS) {
                    val fallback = extractUid(storage)
                    val note = if (fallback != null) {
                        "uid via storage fallback (MAX sync: $state)"
                    } else {
                        "uid NOT captured (MAX sync: $state). $lastDebug".trim()
                    }
                    deliver(token, fallback, note)
                }
            }
        }
    }

    private fun deliver(token: String, uid: String?, debug: String?) {
        if (delivered) return
        delivered = true
        handler.removeCallbacks(pollTask)

        Logx.i("MaxLogin", debug ?: if (uid != null) "token+uid captured" else "token captured")

        val data = Intent().putExtra(EXTRA_TOKEN, token)
        if (!uid.isNullOrEmpty()) data.putExtra(EXTRA_UID, uid)
        if (!debug.isNullOrEmpty()) data.putExtra(EXTRA_DEBUG, debug)

        setResult(Activity.RESULT_OK, data)
        finish()
    }

    /**
     * Builds the in-page script that logs into MAX over its websocket using the
     * captured [token] and stashes the signed-in account's own numeric id on
     * `window.__ofUid`. Mirrors transport/oneme's LoginByToken (opcode 6 + 19,
     * ver 11). A token-free diagnostic is left on `window.__ofDump`.
     */
    private fun uidFetchJs(token: String): String {
        val tokenJson = JSONObject.quote(token)
        return """
            (function () {
              if (window.__ofStarted) return "already";
              window.__ofStarted = true;
              window.__ofUid = "";
              window.__ofDump = "";
              window.__ofState = "connecting";
              try {
                var token = $tokenJson;
                function uuid() {
                  try {
                    return (window.crypto && crypto.randomUUID)
                      ? crypto.randomUUID()
                      : ("d" + Date.now() + Math.floor(Math.random() * 1e9));
                  } catch (e) { return "d" + Date.now(); }
                }
                function findId(obj, depth) {
                  if (obj == null || depth > 6) return "";
                  if (typeof obj === "object") {
                    for (var k in obj) {
                      var lk = ("" + k).toLowerCase();
                      if (lk === "id" || lk === "userid" || lk === "user_id" || lk === "uid" ||
                          lk === "contactid" || lk === "contact_id" ||
                          lk === "accountid" || lk === "account_id") {
                        var v = obj[k];
                        var s = (typeof v === "number") ? String(v)
                               : (typeof v === "string" ? v : "");
                        if (/^\d{5,20}${'$'}/.test(s)) return s;
                      }
                    }
                    for (var k2 in obj) {
                      var r = findId(obj[k2], depth + 1);
                      if (r) return r;
                    }
                  }
                  return "";
                }
                var ws = new WebSocket("wss://ws-api.oneme.ru/websocket");
                var seq = 0;
                function send(op, payload) {
                  seq++;
                  ws.send(JSON.stringify({ ver: 11, cmd: 0, seq: seq, opcode: op, payload: payload }));
                }
                ws.onopen = function () {
                  window.__ofState = "open";
                  send(6, {
                    userAgent: {
                      deviceType: "WEB", locale: "ru_RU", osVersion: "Android",
                      deviceName: "OpenFlux", appVersion: "25.9.15",
                      screen: "1080x2340 2.0x", timezone: "Europe/Moscow"
                    },
                    deviceId: uuid()
                  });
                  send(19, {
                    interactive: true, token: token, chatsSync: 0, contactsSync: 0,
                    presenceSync: 0, draftsSync: 0, chatsCount: 40
                  });
                };
                ws.onmessage = function (ev) {
                  try {
                    var m = JSON.parse(ev.data);
                    if (!m || m.opcode !== 19) return;
                    window.__ofState = "sync";
                    var p = m.payload || {};
                    if (p.error) {
                      window.__ofState = "loginerr";
                      try { window.__ofDump = "error=" + JSON.stringify(p.error); } catch (e0) {}
                      try { ws.close(); } catch (e1) {}
                      return;
                    }
                    var prof = p.profile || p.account || p.me || p.owner || null;
                    var id = prof ? findId(prof, 0) : "";
                    if (!id) {
                      var clone = {};
                      for (var k in p) {
                        if (k !== "contacts" && k !== "chats" &&
                            k !== "presence" && k !== "drafts") clone[k] = p[k];
                      }
                      id = findId(clone, 0);
                    }
                    if (id) { window.__ofUid = id; window.__ofState = "done"; }
                    else window.__ofState = "notfound";
                    try {
                      var keys = Object.keys(p);
                      var snap = prof ? JSON.stringify(prof) : "";
                      if (snap.length > 1200) snap = snap.slice(0, 1200);
                      window.__ofDump = "opcode19 keys=[" + keys.join(",") + "] profile=" + snap;
                    } catch (e2) { window.__ofDump = "dumperr:" + e2; }
                    try { ws.close(); } catch (e3) {}
                  } catch (e) { window.__ofState = "msgerr:" + e; }
                };
                ws.onerror = function () {
                  if (window.__ofState !== "done") window.__ofState = "wserror";
                };
                ws.onclose = function () {
                  if (window.__ofUid === "" && window.__ofState !== "done" &&
                      window.__ofState !== "notfound" && window.__ofState !== "loginerr") {
                    window.__ofState = "closed";
                  }
                };
              } catch (e) { window.__ofState = "exc:" + e; }
              return "started";
            })();
        """.trimIndent()
    }

    /**
     * `evaluateJavascript` hands back a JSON-encoded string, so the payload is
     * double-encoded: unwrap once to get the underlying object.
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

    /**
     * Storage-scan fallback for the numeric id, used only when the websocket
     * lookup does not resolve one. Accepts a bare "id" when it sits inside a
     * container whose key marks it as the current user's own profile/account.
     */
    private fun extractUid(storage: JSONObject): String? =
        searchUid(storage, inSelfContainer = false)

    private fun searchUid(node: Any?, inSelfContainer: Boolean): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val text = scalarText(value)
                    if (text != null && UID_REGEX.matches(text)) {
                        if (matchesHint(key, UID_KEY_HINTS)) return text
                        val lowerKey = key.lowercase()
                        if (inSelfContainer && BARE_ID_KEYS.any { it == lowerKey }) return text
                    }
                    val childInSelf = inSelfContainer || matchesHint(key, SELF_CONTAINER_HINTS)
                    val parsed = parseIfJson(value) ?: value
                    searchUid(parsed, childInSelf)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val value = node.opt(i)
                    val parsed = parseIfJson(value) ?: value
                    searchUid(parsed, inSelfContainer)?.let { return it }
                }
            }
        }
        return null
    }

    private fun scalarText(value: Any?): String? = when (value) {
        is String -> value.trim().ifEmpty { null }
        is Number -> value.toString()
        else -> null
    }

    /** Parses a value that is itself a JSON object/array encoded as a string. */
    private fun parseIfJson(value: Any?): Any? {
        val text = (value as? String)?.trim() ?: return null
        return runCatching {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> null
            }
        }.getOrNull()
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
