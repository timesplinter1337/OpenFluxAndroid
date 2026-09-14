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
import io.github.p1neapplexpress.openflux.data.TunnelState

/**
 * A compact connection-progress strip shown above the log output.
 *
 * It mirrors the app's OWN connection pipeline as reported by
 * [TunnelsViewModel.active] / [TunnelState] — nothing is guessed from log text:
 *
 *   Подключение (Connecting) -> Транспорт (StartingTransport)
 *     -> Tun2socks (StartingTun2Socks) -> Онлайн (Running)
 *
 * States per step:
 *  - PENDING: gray outline, waiting.
 *  - ACTIVE:  blue, gently pulsing — this is the current step.
 *  - DONE:    green with a check — completed.
 *  - ERROR:   red with a "!" — this is where the connection got stuck.
 *
 * Feed it every [TunnelState] via [onState]; it advances the highlight so you
 * can see how far the connection got and exactly which step stalled.
 */
class ConnectPipelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private enum class State { PENDING, ACTIVE, DONE, ERROR }

    /** Ordered pipeline, one node per real TunnelState step. */
    private val stepLabels = listOf("Подключение", "Транспорт", "Tun2socks", "Онлайн")
    private val stepStatus = listOf(
        "Подключение…",
        "Запуск транспорта…",
        "Запуск tun2socks…",
        "Подключено",
    )
    private val stepCount get() = stepLabels.size

    private val idle = 0xFF666666.toInt()
    private val blue = 0xFF4F7CFF.toInt()
    private val green = 0xFF4CAF50.toInt()
    private val red = 0xFFF44336.toInt()
    private val textDim = 0xFF8A92A6.toInt()

    private val states = Array(stepLabels.size) { State.PENDING }
    private val circles = ArrayList<TextView>(stepLabels.size)
    private val labels = ArrayList<TextView>(stepLabels.size)
    private val connectors = ArrayList<View>(stepLabels.size - 1)

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
            text = "Ожидание подключения…"
            setTextColor(textDim)
            textSize = 13f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
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
            // Align to top so connectors line up with the circle centers rather
            // than the taller cell (circle + label).
            gravity = Gravity.TOP
        }
        for (i in 0 until stepCount) {
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
                text = stepLabels[i]
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

    /** Feed the app's real connection state here. */
    fun onState(state: TunnelState) {
        when (state) {
            is TunnelState.Idle -> reset()
            is TunnelState.Connecting -> advanceTo(0)
            is TunnelState.StartingTransport -> advanceTo(1)
            is TunnelState.StartingTun2Socks -> advanceTo(2)
            is TunnelState.Running -> complete()
            is TunnelState.Error -> fail(state.message)
        }
    }

    /** Back to the waiting state. */
    fun reset() {
        stopTimer()
        startedAt = 0L
        timerText.text = "0:00"
        currentActive = -1
        setActiveCircle(-1)
        for (i in states.indices) states[i] = State.PENDING
        render()
        statusText.text = "Ожидание подключения…"
        statusText.setTextColor(textDim)
    }

    // ---- internals --------------------------------------------------------

    private fun advanceTo(step: Int) {
        if (step !in 0 until stepCount) return
        startTimerIfNeeded()
        for (j in 0 until step) states[j] = State.DONE
        states[step] = State.ACTIVE
        for (j in step + 1 until stepCount) states[j] = State.PENDING
        currentActive = step
        render()
        setActiveCircle(step)
        statusText.text = stepStatus[step]
        statusText.setTextColor(blue)
    }

    private fun complete() {
        for (i in states.indices) states[i] = State.DONE
        currentActive = stepCount - 1
        render()
        setActiveCircle(-1)
        statusText.text = "Подключено"
        statusText.setTextColor(green)
        stopTimer()
    }

    private fun fail(message: String) {
        val idx = if (currentActive in 0 until stepCount) currentActive else 0
        states[idx] = State.ERROR
        currentActive = idx
        render()
        setActiveCircle(-1)
        val reason = message.trim()
        statusText.text = if (reason.isEmpty()) {
            "Застряло: ${stepLabels[idx]}"
        } else {
            "Застряло: ${stepLabels[idx]} — $reason"
        }
        statusText.setTextColor(red)
        stopTimer()
    }

    private fun render() {
        for (i in states.indices) applyState(i, states[i])
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
            // connector k sits between step k and step k+1.
            connectors[k].setBackgroundColor(if (states[k] == State.DONE) green else idle)
        }
    }

    private fun setActiveCircle(i: Int) {
        pulse?.cancel()
        pulse = null
        pulseTarget?.alpha = 1f
        pulseTarget = null
        if (i !in 0 until stepCount) return
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
}
