package com.example.a180d

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the floating BPM overlay: the `WindowManager` view, its gestures, its
 * remembered position and the tick that keeps it current.
 *
 * Lives inside [BleHeartRateService] rather than a service of its own — that
 * service already runs for exactly the session's lifetime and already holds
 * every flow the bubble needs, and an overlay needs no foreground-service type.
 *
 * Everything here runs on the main thread: the service's own scope is
 * `Dispatchers.Default`, and `WindowManager` calls must not happen off-main.
 */
class BubbleController(
    private val context: Context,
    private val userSettings: UserSettings,
    private val latestSample: StateFlow<HeartRateSample?>,
    private val connectionState: StateFlow<ConnectionState>,
    private val onTapped: () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var view: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var targetView: DismissTargetView? = null
    private var renderJob: Job? = null
    private var snapAnimator: ValueAnimator? = null

    private var enabled = userSettings.loadBubbleEnabled()
    private var sessionActive = false
    private var appForeground = true
    private var dismissedForSession = false

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appForeground = true
            sync()
        }

        override fun onStop(owner: LifecycleOwner) {
            appForeground = false
            sync()
        }
    }

    init {
        // ProcessLifecycleOwner rather than a binder call from MainActivity's
        // onStart/onStop: it debounces configuration changes, so a rotation
        // doesn't flash the bubble, and it can't go stale if the Activity is
        // destroyed without onStop.
        scope.launch {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            appForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            lifecycle.addObserver(processObserver)
            sync()
        }
    }

    // ---- External state ----------------------------------------------------

    fun onSessionStarted() {
        sessionActive = true
        dismissedForSession = false
        // Re-read in case the user granted the permission or flipped the
        // toggle while no session was running.
        enabled = userSettings.loadBubbleEnabled()
        scope.launch { sync() }
    }

    fun onSessionEnded() {
        sessionActive = false
        scope.launch { sync() }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        scope.launch { sync() }
    }

    /** Called from `Service.onDestroy` on the main thread — tears the view down synchronously, before the scope that would otherwise have done it is cancelled. */
    fun destroy() {
        hide()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        scope.cancel()
    }

    // ---- Show / hide -------------------------------------------------------

    private fun shouldShow(): Boolean = enabled &&
        sessionActive &&
        !appForeground &&
        !dismissedForSession &&
        Settings.canDrawOverlays(context)

    private fun sync() {
        if (shouldShow()) show() else hide()
    }

    private fun show() {
        if (view != null) return
        val bubble = BubbleView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        restorePosition(params)
        bubble.setOnClickListener { onTapped() }
        bubble.setOnTouchListener(BubbleTouchListener())

        // Drag-to-target is a pointer gesture, so on its own it would leave a
        // screen-reader user with no way to dismiss the bubble at all. This
        // exposes the same action in TalkBack's actions menu.
        ViewCompat.addAccessibilityAction(bubble, context.getString(R.string.bubble_hide_action)) { _, _ ->
            dismissForSession()
            true
        }

        // The dismiss target goes up FIRST and stays up, invisible, for the
        // whole session. Two TYPE_APPLICATION_OVERLAY windows from one app
        // stack in the order they were added and there is no public way to set
        // z within a type, so adding it lazily on drag start would put the
        // scrim *over* the bubble being dragged.
        addDismissTarget()

        // addView can still throw if the permission was revoked between the
        // check and here, or on an OEM that blocks overlays outright.
        val added = runCatching { windowManager.addView(bubble, params) }.isSuccess
        if (!added) {
            removeDismissTarget()
            return
        }

        view = bubble
        layoutParams = params
        startRenderLoop(bubble)
    }

    private fun addDismissTarget() {
        val target = DismissTargetView(context).apply {
            visibility = View.GONE
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(DismissTargetView.TARGET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCHABLE is load-bearing: the target is never tapped, only
            // dropped onto, and a touchable window here would steal the drag
            // stream from the bubble mid-gesture.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        // A failure here is survivable — the bubble still works, it just can't
        // be dragged away (the accessibility action still hides it).
        if (runCatching { windowManager.addView(target, params) }.isSuccess) {
            targetView = target
        }
    }

    private fun removeDismissTarget() {
        val target = targetView ?: return
        targetView = null
        runCatching { windowManager.removeView(target) }
    }

    private fun showDismissTarget() {
        val target = targetView ?: return
        target.animate().cancel()
        target.visibility = View.VISIBLE
        target.animate().alpha(1f).setDuration(TARGET_FADE_MS).start()
    }

    private fun hideDismissTarget() {
        val target = targetView ?: return
        target.armed = false
        target.animate().cancel()
        target.animate()
            .alpha(0f)
            .setDuration(TARGET_FADE_MS)
            .withEndAction { target.visibility = View.GONE }
            .start()
    }

    /**
     * Centre of the dismiss circle in the bubble's own coordinate space.
     *
     * Both windows omit FLAG_LAYOUT_NO_LIMITS, so both are laid out inside the
     * same system-bar frame and the target's bottom edge is the bottom of the
     * usable area — no cross-window conversion needed.
     */
    private fun dismissCentre(usable: android.graphics.Point): android.graphics.Point =
        android.graphics.Point(
            usable.x / 2,
            usable.y - dp(DismissTargetView.CIRCLE_BOTTOM_MARGIN_DP),
        )

    /** Shrinks the bubble into the target, then tears it down for the session. */
    private fun dismissWithAnimation() {
        val bubble = view
        if (bubble == null) {
            dismissForSession()
            return
        }
        targetView?.animate()?.alpha(0f)?.setDuration(DISMISS_ANIM_MS)?.start()
        bubble.animate()
            .alpha(0f)
            .scaleX(DISMISS_END_SCALE)
            .scaleY(DISMISS_END_SCALE)
            .setDuration(DISMISS_ANIM_MS)
            .withEndAction { dismissForSession() }
            .start()
    }

    private fun hide() {
        renderJob?.cancel()
        renderJob = null
        snapAnimator?.cancel()
        snapAnimator = null
        removeDismissTarget()
        val current = view ?: return
        view = null
        layoutParams = null
        runCatching { windowManager.removeView(current) }
    }

    /**
     * Redraws on every new sample and state change, plus a steady tick so the
     * reading visibly goes stale at 5s even when the stream has stopped
     * producing events entirely — the frozen-number failure this whole surface
     * has to avoid.
     */
    private fun startRenderLoop(bubble: BubbleView) {
        renderJob?.cancel()
        renderJob = scope.launch {
            launch { latestSample.collect { render(bubble) } }
            launch { connectionState.collect { render(bubble) } }
            launch {
                while (isActive) {
                    render(bubble)
                    delay(RENDER_TICK_MS)
                }
            }
        }
    }

    private fun render(bubble: BubbleView) {
        bubble.update(
            bubbleRenderState(
                sample = latestSample.value,
                connectionState = connectionState.value,
                nowMs = System.currentTimeMillis(),
                settings = userSettings.load(),
            ),
        )
    }

    // ---- Position ----------------------------------------------------------

    private fun restorePosition(params: WindowManager.LayoutParams) {
        val usable = usableSize()
        val viewWidth = dp(BubbleView.PILL_WIDTH_DP + BubbleView.SHADOW_PAD_DP * 2)
        val viewHeight = dp(BubbleView.PILL_HEIGHT_DP + BubbleView.SHADOW_PAD_DP * 2)

        params.x = if (userSettings.loadBubbleOnLeftEdge()) {
            EDGE_MARGIN_PX
        } else {
            (usable.x - viewWidth - EDGE_MARGIN_PX).coerceAtLeast(EDGE_MARGIN_PX)
        }
        val yRange = (usable.y - viewHeight).coerceAtLeast(0)
        params.y = (yRange * userSettings.loadBubbleYFraction()).roundToInt()
    }

    private fun persistPosition() {
        val params = layoutParams ?: return
        val usable = usableSize()
        val viewWidth = view?.width ?: dp(BubbleView.PILL_WIDTH_DP + BubbleView.SHADOW_PAD_DP * 2)
        val viewHeight = view?.height ?: dp(BubbleView.PILL_HEIGHT_DP + BubbleView.SHADOW_PAD_DP * 2)
        val onLeft = params.x + viewWidth / 2 <= usable.x / 2
        val yRange = (usable.y - viewHeight).coerceAtLeast(1)
        val fraction = (params.y.toFloat() / yRange).coerceIn(0f, 1f)
        userSettings.saveBubblePosition(onLeftEdge = onLeft, yFraction = fraction)
    }

    /**
     * Size of the area the bubble can occupy, as (width, height).
     *
     * A TYPE_APPLICATION_OVERLAY window without FLAG_LAYOUT_NO_LIMITS is
     * already laid out inside the system bars and the cutout, so its x/y are
     * relative to *that* frame, not the display. Everything here is therefore
     * 0-origin: adding the inset offsets would count them twice and push the
     * bubble down by a status bar's height.
     */
    private fun usableSize(): android.graphics.Point {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return android.graphics.Point(
            metrics.bounds.width() - insets.left - insets.right,
            metrics.bounds.height() - insets.top - insets.bottom,
        )
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val bubble = view ?: return
        val target = snapToNearestEdgeX(
            currentX = params.x,
            viewWidth = bubble.width,
            screenWidth = usableSize().x,
            marginPx = EDGE_MARGIN_PX,
        )

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = OvershootInterpolator(SNAP_OVERSHOOT)
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                runCatching { windowManager.updateViewLayout(bubble, params) }
            }
            start()
        }
        persistPosition()
    }

    // ---- Gestures ----------------------------------------------------------

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downParamX = 0
        private var downParamY = 0
        private var dragging = false
        private var armed = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val bubble = v as BubbleView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    dragging = false
                    armed = false
                    snapAnimator?.cancel()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        bubble.dragging = true
                        showDismissTarget()
                    }
                    if (dragging) {
                        val usable = usableSize()
                        val proposedX = (downParamX + dx).roundToInt()
                            .coerceIn(0, (usable.x - v.width).coerceAtLeast(0))
                        val proposedY = (downParamY + dy).roundToInt()
                            .coerceIn(0, (usable.y - v.height).coerceAtLeast(0))

                        val centre = dismissCentre(usable)
                        val nowArmed = targetView != null && isWithinDismissMagnet(
                            bubbleCentreX = proposedX + v.width / 2,
                            bubbleCentreY = proposedY + v.height / 2,
                            targetCentreX = centre.x,
                            targetCentreY = centre.y,
                            magnetRadiusPx = dp(DismissTargetView.MAGNET_RADIUS_DP),
                        )
                        if (nowArmed != armed) {
                            armed = nowArmed
                            bubble.armed = nowArmed
                            targetView?.armed = nowArmed
                            // One tick on entry only, so sliding around inside
                            // the magnet doesn't buzz continuously.
                            if (nowArmed) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }

                        // Once armed the bubble sits in the target rather than
                        // under the finger, so the drop point is unambiguous.
                        if (armed) {
                            params.x = centre.x - v.width / 2
                            params.y = centre.y - v.height / 2
                        } else {
                            params.x = proposedX
                            params.y = proposedY
                        }
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    bubble.dragging = false
                    when {
                        dragging && armed -> dismissWithAnimation()
                        dragging -> {
                            bubble.armed = false
                            hideDismissTarget()
                            snapToEdge()
                        }
                        // performClick rather than onTapped() directly, so
                        // accessibility services can trigger the same path.
                        isTap(event) -> v.performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // A cancelled gesture must never dismiss.
                    bubble.dragging = false
                    bubble.armed = false
                    armed = false
                    hideDismissTarget()
                    if (dragging) snapToEdge()
                    return true
                }
            }
            return false
        }

        /**
         * Movement alone decides this. There is no long-press any more, so a
         * slow stationary press-and-release is a tap, the way it is on any
         * ordinary View.
         */
        private fun isTap(event: MotionEvent): Boolean =
            abs(event.rawX - downRawX) <= touchSlop && abs(event.rawY - downRawY) <= touchSlop
    }

    private fun dismissForSession() {
        dismissedForSession = true
        sync()
        Toast.makeText(context, R.string.bubble_dismissed, Toast.LENGTH_SHORT).show()
    }

    // ---- Helpers -----------------------------------------------------------

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).roundToInt()

    companion object {
        /**
         * Zero, because [BubbleView] already carries `SHADOW_PAD_DP` of
         * transparent padding on each side — that padding *is* the visible gap
         * between the pill and the screen edge. Adding a margin on top would
         * park the pill a shadow-width further in than intended.
         */
        private const val EDGE_MARGIN_PX = 0

        private const val SNAP_DURATION_MS = 220L
        private const val SNAP_OVERSHOOT = 1.1f
        private const val TARGET_FADE_MS = 150L
        private const val DISMISS_ANIM_MS = 150L
        private const val DISMISS_END_SCALE = 0.4f

        /**
         * Drives the staleness transition. Fast enough that the 5s cutoff lands
         * visibly on time, slow enough to be invisible on the battery.
         */
        private const val RENDER_TICK_MS = 500L

        /** Sends the user back to the app when the bubble is tapped. */
        fun launchAppIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
