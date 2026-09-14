package io.github.p1neapplexpress.openflux.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A compact connection-progress strip shown above the log output. It watches the
 * log stream and lights up pipeline stages (DNS -> VK -> WRAP -> TURN -> Потоки
 * -> Raw) as the core reports progress, so the user can see how far the
 * connection got and where it stalled.
 *
 * States per stage:
 *  - PENDING: gray outline, waiting.
 *  - ACTIVE:  blue, gently pulsing — this is the current step.
 *  - DONE:    green with a check — completed.
 *  - ERROR:   red with a "!" — this is where the connection got stuck.
 *
 * Stage detection is deliberately keyword based and forgiving: the stage names
 * arrive as free-form log text from the bundled native core (Russian, e.g.
 * "[СЕТЬ] DNS доступен…", with English fallbacks). This view only augments the
 * log; it never filters or blocks it.
 */
class ConnectPipelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private enum class State { PENDING, ACTIVE, DONE, ERROR }

    private data class Stage(val label: String, val keys: List<String>)

    /** Ordered pipeline. Keys are matched case-insensitively against each line. */
    private val stages = listOf(
        Stage("DNS", listOf("dns", "doh", " dot")),
        Stage("VK", listOf("vkcall", "режим vk", " vk:", "[vk", " vk ")),
        Stage("WRAP", listOf("wrap", "aead", "ключ вывед", "derive")),
        Stage("TURN", listOf("turn", "stun", " ice", "peerconnection", "peer connection")),
        Stage("Потоки", listOf("поток", "stream", "rtp", "медиа", "media", "srtp")),
        Stage("Raw", listOf("raw", "туннел", "tunnel", "tun2socks")),
    )

    private val idle = 0xFF666666.toInt()
    private val blue = 0xFF4F7CFF.toInt()
    private val green = 0xFF4CAF50.toInt()
    private val red = 0xFFF44336.toInt()
    private val textDim = 0xFF8A92A6.toInt()

    private val states = Array(stages.size) { State.PENDING }
    private val circles = ArrayList<TextView>(stages.size)
    private val labels = ArrayList<TextView>(stages.size)
    private val connectors = ArrayList<View>(stages.size - 1)

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView

    private var currentActive = -1
    private var startedAt = 0L
    private var timerRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: ValueAnimator? = null
    private var pulseTarget: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
            timerText.text = String.format("%d:%02d", secs / 60, secs % 60)
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        val padH = dp(12)
        setPadding(padH, dp(10), padH, dp(10))
        setBackgroundColor(0xFF0F1420.toInt())
        buildHeader()
        buildSteps()
        render()
    }

    private fun buildHeader() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = TextView(context).apply {
            text = "Ожидание данных…"
            setTextColor(blue)
            textSize = 13f
            setSingleLine()
        }
        timerText = TextView(context).apply {
            text = "0:00"
            setTextColor(textDim)
            textSize = 13f
        }
        row.addView(statusText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(timerText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildSteps() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            // Align to top so the connectors line up with the circle centers
            // rather than the taller cell (circle + label).
            gravity = Gravity.TOP
        }
        for (i in stages.indices) {
            if (i > 0) {
                val connector = View(context)
                val lp = LayoutParams(0, dp(2), 1f)
                lp.topMargin = dp(11) // ~ half the 24dp circle height
                connector.setBackgroundColor(idle)
                row.addView(connector, lp)
                connectors.add(connector)
            }
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val circle = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                val s = dp(24)
                layoutParams = LayoutParams(s, s)
            }
            val label = TextView(context).apply {
                text = stages[i].label
                textSize = 10f
                setTextColor(textDim)
                gravity = Gravity.CENTER
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4)
                layoutParams = lp
            }
            cell.addView(circle)
            cell.addView(label)
            circles.add(circle)
            labels.add(label)
            row.addView(cell, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val rowLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(12)
        addView(row, rowLp)
    }

    // ---- public API -------------------------------------------------------

    /** Feed every raw log line here. Safe to call for every message. */
    fun onLog(raw: String) {
        raw.split('\n').forEach { part ->
            val line = part.trim()
            if (line.isNotEmpty()) processLine(line.lowercase())
        }
    }

    /** Connection fully established: mark everything done. */
    fun onConnected() {
        for (i in stages.indices) {
            states[i] = State.DONE
            applyState(i, State.DONE)
        }
        currentActive = stages.size - 1
        setActiveCircle(-1)
        updateConnectors()
        statusText.text = "Подключено"
        statusText.setTextColor(green)
        stopTimer()
    }

    /** Disconnected / stopped: clear the strip back to the waiting state. */
    fun reset() {
        stopTimer()
        startedAt = 0L
        timerText.text = "0:00"
        currentActive = -1
        setActiveCircle(-1)
        for (i in stages.indices) states[i] = State.PENDING
        render()
        statusText.text = "Ожидание данных…"
        statusText.setTextColor(blue)
    }

    // ---- internals --------------------------------------------------------

    private fun processLine(line: String) {
        val isError = ERR_KEYS.any { line.contains(it) }
        val isOk = OK_KEYS.any { line.contains(it) }

        var matched = -1
        for (i in stages.indices) {
            if (stages[i].keys.any { line.contains(it) }) { matched = i; break }
        }

        if (matched < 0) {
            // No stage keyword; if we're mid-connect and hit an error, flag the
            // current step as the bottleneck.
            if (isError && currentActive in stages.indices && states[currentActive] != State.DONE) {
                markError(currentActive)
            }
            return
        }

        startTimerIfNeeded()

        // Any stage before the matched one is implicitly complete.
        for (j in 0 until matched) {
            if (states[j] != State.ERROR && states[j] != State.DONE) {
                states[j] = State.DONE
                applyState(j, State.DONE)
            }
        }
        currentActive = maxOf(currentActive, matched)

        when {
            isError -> markError(matched)
            isOk || states[matched] == State.DONE -> markDone(matched)
            else -> markActive(matched)
        }
        updateConnectors()
    }

    private fun markActive(i: Int) {
        states[i] = State.ACTIVE
        applyState(i, State.ACTIVE)
        currentActive = i
        setActiveCircle(i)
        statusText.text = stages[i].label
        statusText.setTextColor(blue)
    }

    private fun markDone(i: Int) {
        states[i] = State.DONE
        applyState(i, State.DONE)
        val next = states.indexOfFirst { it == State.PENDING }
        if (next in stages.indices) {
            // Advance the highlight to the next step so the strip visibly moves.
            markActive(next)
        } else {
            setActiveCircle(-1)
            statusText.text = "Подключено"
            statusText.setTextColor(green)
            stopTimer()
        }
    }

    private fun markError(i: Int) {
        states[i] = State.ERROR
        applyState(i, State.ERROR)
        currentActive = i
        setActiveCircle(-1)
        updateConnectors()
        statusText.text = "Застряло: ${stages[i].label}"
        statusText.setTextColor(red)
    }

    private fun render() {
        for (i in stages.indices) applyState(i, states[i])
        updateConnectors()
    }

    private fun applyState(i: Int, st: State) {
        val circle = circles[i]
        val label = labels[i]
        val color = when (st) {
            State.PENDING -> idle
            State.ACTIVE -> blue
            State.DONE -> green
            State.ERROR -> red
        }
        val filled = st == State.DONE || st == State.ERROR
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (filled) color else Color.TRANSPARENT)
            setStroke(dp(2), color)
        }
        circle.text = when (st) {
            State.DONE -> "✓"
            State.ERROR -> "!"
            else -> ""
        }
        circle.setTextColor(Color.WHITE)
        circle.alpha = 1f
        label.setTextColor(if (st == State.PENDING) textDim else color)
    }

    private fun updateConnectors() {
        for (k in connectors.indices) {
            // connector k sits between stage k and stage k+1.
            connectors[k].setBackgroundColor(if (states[k] == State.DONE) green else idle)
        }
    }

    private fun setActiveCircle(i: Int) {
        pulse?.cancel()
        pulse = null
        pulseTarget?.alpha = 1f
        pulseTarget = null
        if (i !in stages.indices) return
        val target = circles[i]
        pulseTarget = target
        pulse = ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { target.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun startTimerIfNeeded() {
        if (timerRunning) return
        timerRunning = true
        startedAt = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    private fun stopTimer() {
        timerRunning = false
        handler.removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stopTimer()
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private val ERR_KEYS = listOf(
            "ошибк", "error", "fail", "malformed", "retry", "не удал",
            "timeout", "таймаут", "отказ", "refused", "недоступ",
        )
        private val OK_KEYS = listOf(
            "[ok", "ok:", " ok ", "доступен", "актив", "успеш", "вывед",
            "connected", "подключ", "готов", "established", "ready",
        )
    }
}
