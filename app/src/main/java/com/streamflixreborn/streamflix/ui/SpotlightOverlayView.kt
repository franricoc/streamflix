package com.streamflixreborn.streamflix.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.streamflixreborn.streamflix.R

class SpotlightOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6000000") // 90% dark overlay for clear contrast
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#E50914") // Streamflix red
    }

    private var targetRect = RectF()
    private var isTargetSet = false
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun highlightTarget(targetView: View, tooltipText: String, onDismiss: () -> Unit) {
        val updateTargetBounds = {
            val location = IntArray(2)
            targetView.getLocationInWindow(location)

            val myLocation = IntArray(2)
            getLocationInWindow(myLocation)

            val left = (location[0] - myLocation[0]).toFloat()
            val top = (location[1] - myLocation[1]).toFloat()
            val right = left + targetView.width
            val bottom = top + targetView.height

            val padding = 20f
            targetRect.set(left - padding, top - padding, right + padding, bottom + padding)
            isTargetSet = true

            removeAllViews()
            val cardView = LayoutInflater.from(context).inflate(R.layout.view_spotlight_tooltip, this, false)
            val tvMessage = cardView.findViewById<TextView>(R.id.tvSpotlightMessage)
            val btnGotIt = cardView.findViewById<Button>(R.id.btnSpotlightGotIt)
            tvMessage.text = tooltipText

            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (top - 350 > 0) {
                params.bottomMargin = (height - top + 30).toInt()
                params.gravity = android.view.Gravity.BOTTOM
            } else {
                params.topMargin = (bottom + 30).toInt()
                params.gravity = android.view.Gravity.TOP
            }
            params.leftMargin = 48
            params.rightMargin = 48
            cardView.layoutParams = params
            addView(cardView)

            val dismissAction = View.OnClickListener {
                stopPulseAnimation()
                onDismiss()
            }

            setOnClickListener(dismissAction)
            btnGotIt?.setOnClickListener(dismissAction)

            startPulseAnimation()
            invalidate()
        }

        if (targetView.width > 0 && targetView.height > 0) {
            post(updateTargetBounds)
        } else {
            targetView.post(updateTargetBounds)
        }
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        pulseAnimator = ValueAnimator.ofFloat(0f, 45f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedFraction
                pulseRadius = anim.animatedValue as Float
                pulseAlpha = ((1f - progress) * 255).toInt()
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isTargetSet) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        val cx = targetRect.centerX()
        val cy = targetRect.centerY()
        val radius = (Math.max(targetRect.width(), targetRect.height()) / 2f).coerceAtLeast(35f)

        // Clear circle over target
        canvas.drawCircle(cx, cy, radius, targetPaint)

        // Animated red pulse ring
        pulsePaint.alpha = pulseAlpha
        canvas.drawCircle(cx, cy, radius + pulseRadius, pulsePaint)
    }
}
