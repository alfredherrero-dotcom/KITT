package com.kitt.audiobridge

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Small always-on-top edge tab ("Pestaña KITT") that toggles the fullscreen
 * panel. Owned and shown/hidden by AudioWatchService.
 */
class EdgeHandleOverlay(private val service: Service) {

    companion object {
        private const val WIDTH_DP = 22f
        private const val HEIGHT_DP = 72f
        private const val CORNER_RADIUS_DP = 12f
        private const val TOUCH_SLOP_DP = 10f
        private const val IDLE_DIM_DELAY_MS = 5000L
        private const val DIMMED_ALPHA = 0.35f
        private const val FULL_ALPHA = 1f
        private const val PRESS_SCALE = 0.92f
    }

    private val windowManager = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
    private val dimHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { setDimmed(true) }

    private var handleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dockedRight = true

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var isDragging = false

    fun show() {
        if (handleView != null) return

        dockedRight = AppPreferences.isEdgeHandleDockedRight(service)

        val view = View(service)
        val params = buildLayoutParams()

        val screenHeight = screenSize().second
        val height = dpToPx(HEIGHT_DP)
        val savedY = AppPreferences.getEdgeHandleY(service)
        params.y = if (savedY >= 0) savedY else (screenHeight - height) / 2
        applyDockedX(params)

        view.setOnTouchListener { v, event -> onTouch(v, event) }

        handleView = view
        layoutParams = params
        applyBackground(view)
        windowManager.addView(view, params)
        scheduleDim()
    }

    fun hide() {
        val view = handleView ?: return
        dimHandler.removeCallbacks(dimRunnable)
        runCatching { windowManager.removeView(view) }
        handleView = null
        layoutParams = null
    }

    fun isShown(): Boolean = handleView != null

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            dpToPx(WIDTH_DP),
            dpToPx(HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        return params
    }

    private fun applyBackground(view: View) {
        val radius = dpToPx(CORNER_RADIUS_DP).toFloat()
        val radii = if (dockedRight) {
            // Flush against the right edge: round only the left-facing corners.
            floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        } else {
            // Flush against the left edge: round only the right-facing corners.
            floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        }
        view.background = GradientDrawable().apply {
            setColor(Color.parseColor("#ff2a1a"))
            setStroke(dpToPx(1f), Color.parseColor("#0a0a0a"))
            cornerRadii = radii
        }
    }

    private fun applyDockedX(params: WindowManager.LayoutParams) {
        val screenWidth = screenSize().first
        params.x = if (dockedRight) screenWidth - dpToPx(WIDTH_DP) else 0
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                isDragging = false

                dimHandler.removeCallbacks(dimRunnable)
                setDimmed(false)
                view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(80).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!isDragging && (abs(dx) > dpToPx(TOUCH_SLOP_DP) || abs(dy) > dpToPx(TOUCH_SLOP_DP))) {
                    isDragging = true
                }

                if (isDragging) {
                    val (screenWidth, screenHeight) = screenSize()
                    val viewWidth = dpToPx(WIDTH_DP)
                    val viewHeight = dpToPx(HEIGHT_DP)

                    params.x = (startX + dx.toInt()).coerceIn(0, screenWidth - viewWidth)
                    params.y = (startY + dy.toInt()).coerceIn(0, screenHeight - viewHeight)
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

                if (isDragging) {
                    val screenWidth = screenSize().first
                    val viewWidth = dpToPx(WIDTH_DP)
                    val centerX = params.x + viewWidth / 2
                    dockedRight = centerX > screenWidth / 2

                    applyDockedX(params)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    applyBackground(view)

                    AppPreferences.setEdgeHandleDockedRight(service, dockedRight)
                    AppPreferences.setEdgeHandleY(service, params.y)
                } else {
                    handleTap()
                }

                scheduleDim()
                return true
            }
        }
        return false
    }

    private fun handleTap() {
        if (AppState.panelVisible.value) {
            AppState.requestMinimizePanel?.invoke()
        } else {
            val intent = Intent(service, PanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            service.startActivity(intent)
        }
    }

    private fun scheduleDim() {
        dimHandler.removeCallbacks(dimRunnable)
        dimHandler.postDelayed(dimRunnable, IDLE_DIM_DELAY_MS)
    }

    private fun setDimmed(dimmed: Boolean) {
        handleView?.animate()
            ?.alpha(if (dimmed) DIMMED_ALPHA else FULL_ALPHA)
            ?.setDuration(200)
            ?.start()
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * service.resources.displayMetrics.density).toInt()
    }
}
