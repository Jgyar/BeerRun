package com.beerrun.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Crowd {
    companion object {
        const val MAX_VISUAL_MUGS = 160
        private const val GOLDEN_ANGLE = 2.3999632f
    }

    class MugSlot {
        var offsetX = 0f
        var offsetZ = 0f
        var currentX = 0f
        var currentZ = 0f
        var bobPhase = 0f
    }

    var count: Int = Config.START_COUNT
        private set

    var centerX: Float = 0f
    var crowdRadius: Float = 0.08f
        private set

    var hasShield: Boolean = false
    var hasMagnet: Boolean = false

    private val slots = Array(MAX_VISUAL_MUGS) { MugSlot() }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val foamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 255, 255, 255)
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 160, 0)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 20, 8)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tempRect = RectF()
    private val tempPath = Path()
    private val hsvArray = FloatArray(3)

    init {
        computeSpiralOffsets()
        reset(Config.START_COUNT)
    }

    private fun computeSpiralOffsets() {
        for (i in 0 until MAX_VISUAL_MUGS) {
            if (i == 0) {
                slots[i].offsetX = 0f
                slots[i].offsetZ = 0f
            } else {
                val r = 0.055f * sqrt(i.toFloat())
                val theta = i * GOLDEN_ANGLE
                slots[i].offsetX = r * cos(theta)
                slots[i].offsetZ = r * sin(theta) * 0.75f
            }
            slots[i].bobPhase = i * 0.45f
        }
    }

    fun reset(startCount: Int) {
        centerX = 0f
        hasShield = false
        hasMagnet = false
        count = startCount.coerceIn(1, Config.MAX_MUGS_CAP)
        updateRadius()
        for (i in 0 until MAX_VISUAL_MUGS) {
            slots[i].currentX = centerX + slots[i].offsetX
            slots[i].currentZ = slots[i].offsetZ
        }
    }

    fun setCount(newCount: Int) {
        count = newCount.coerceIn(0, Config.MAX_MUGS_CAP)
        updateRadius()
    }

    fun addCount(delta: Int) {
        setCount(count + delta)
    }

    fun applyGate(op: Config.GateOp, value: Int) {
        val next = when (op) {
            Config.GateOp.ADD -> count + value
            Config.GateOp.SUB -> count - value
            Config.GateOp.MUL -> count * value
            Config.GateOp.DIV -> if (value > 0) count / value else count
        }
        setCount(next)
    }

    private fun updateRadius() {
        val visible = visibleCount()
        crowdRadius = if (visible <= 1) {
            0.07f
        } else {
            (0.06f * sqrt(visible.toFloat())).coerceIn(0.08f, 0.62f)
        }
    }

    fun visibleCount(): Int {
        if (count <= 0) return 0
        if (count <= 60) return count
        val extra = ((count - 60) / 30).coerceAtMost(MAX_VISUAL_MUGS - 60)
        return (60 + extra).coerceAtMost(MAX_VISUAL_MUGS)
    }

    fun update(dt: Float, timeSec: Float) {
        val maxCenter = (Config.TRACK_HALF_WIDTH - crowdRadius * 0.65f).coerceAtLeast(0.15f)
        centerX = centerX.coerceIn(-maxCenter, maxCenter)

        val visible = visibleCount()
        val lerp = (dt * 14f).coerceIn(0.05f, 1f)
        for (i in 0 until visible) {
            val s = slots[i]
            val targetX = (centerX + s.offsetX).coerceIn(-0.96f, 0.96f)
            val targetZ = s.offsetZ
            s.currentX += (targetX - s.currentX) * lerp
            s.currentZ += (targetZ - s.currentZ) * lerp
        }
    }

    fun draw(
        canvas: Canvas,
        screenCenterX: Float,
        baseScreenY: Float,
        trackHalfPx: Float,
        skin: Config.MugSkinDef,
        timeSec: Float
    ) {
        val visible = visibleCount()
        if (visible <= 0) return

        if (hasMagnet) {
            val pulse = ((timeSec * 2.2f) % 1f)
            strokePaint.color = Color.argb(((1f - pulse) * 160).toInt(), 255, 82, 82)
            strokePaint.strokeWidth = 5f
            val r = (crowdRadius * trackHalfPx + 28f) * (0.7f + 0.5f * pulse)
            tempRect.set(
                screenCenterX + centerX * trackHalfPx - r,
                baseScreenY - r * 0.35f,
                screenCenterX + centerX * trackHalfPx + r,
                baseScreenY + r * 0.35f
            )
            canvas.drawOval(tempRect, strokePaint)
        }

        // Увеличенный размер кружек для лучшей видимости
        val mugW = (trackHalfPx * 0.135f).coerceIn(28f, 54f)
        val mugH = mugW * 1.38f

        for (i in visible - 1 downTo 0) {
            val s = slots[i]
            val sx = screenCenterX + s.currentX * trackHalfPx
            val bob = sin(timeSec * 10f + s.bobPhase) * 4.5f
            val sy = baseScreenY - s.currentZ * (trackHalfPx * 0.42f) - bob
            drawSingleMug(canvas, sx, sy, mugW, mugH, skin, timeSec + i * 0.17f)
        }

        if (hasShield) {
            val shieldR = (crowdRadius * trackHalfPx + mugW * 0.95f)
            val cx = screenCenterX + centerX * trackHalfPx
            val cy = baseScreenY - mugH * 0.45f
            bodyPaint.color = Color.argb(60, 129, 212, 250)
            canvas.drawCircle(cx, cy, shieldR, bodyPaint)
            strokePaint.color = Color.argb(215, 225, 245, 254)
            strokePaint.strokeWidth = 6f
            canvas.drawCircle(cx, cy, shieldR, strokePaint)
        }

        // Крупное хорошо читаемое облачко со счётчиком кружек
        val badgeCx = screenCenterX + centerX * trackHalfPx
        val badgeCy = baseScreenY - mugH * 1.68f - min(visible, 40) * 0.48f
        val label = count.toString()
        badgeTextPaint.textSize = (mugW * 0.85f).coerceIn(34f, 52f)
        val textW = badgeTextPaint.measureText(label)
        val boxW = (textW + 46f).coerceAtLeast(88f)
        val boxH = badgeTextPaint.textSize + 24f

        tempRect.set(badgeCx - boxW * 0.5f, badgeCy - boxH * 0.5f, badgeCx + boxW * 0.5f, badgeCy + boxH * 0.5f)
        canvas.drawRoundRect(tempRect, boxH * 0.5f, boxH * 0.5f, badgeBgPaint)
        canvas.drawRoundRect(tempRect, boxH * 0.5f, boxH * 0.5f, badgeBorderPaint)
        canvas.drawText(label, badgeCx, badgeCy + badgeTextPaint.textSize * 0.34f, badgeTextPaint)
    }

    fun drawSingleMug(
        canvas: Canvas,
        cx: Float,
        bottomY: Float,
        w: Float,
        h: Float,
        skin: Config.MugSkinDef,
        animTime: Float
    ) {
        val halfW = w * 0.5f
        val topY = bottomY - h

        if (skin.shapeType == 11) {
            hsvArray[0] = (animTime * 140f) % 360f
            hsvArray[1] = 0.85f
            hsvArray[2] = 0.98f
            bodyPaint.color = Color.HSVToColor(hsvArray)
            hsvArray[2] = 0.72f
            darkPaint.color = Color.HSVToColor(hsvArray)
        } else {
            bodyPaint.color = skin.bodyColor
            darkPaint.color = skin.darkColor
        }
        foamPaint.color = skin.foamColor
        accentPaint.color = skin.accentColor

        if (skin.shapeType == 8) {
            accentPaint.color = Color.argb(95, 0, 229, 255)
            tempRect.set(cx - halfW * 1.35f, topY - h * 0.12f, cx + halfW * 1.35f, bottomY + h * 0.08f)
            canvas.drawRoundRect(tempRect, halfW * 0.6f, halfW * 0.6f, accentPaint)
            accentPaint.color = skin.accentColor
        }

        if (skin.shapeType == 10) {
            val flicker = sin(animTime * 18f) * w * 0.14f
            tempPath.reset()
            tempPath.moveTo(cx - halfW * 1.1f, bottomY - h * 0.2f)
            tempPath.quadTo(cx - halfW * 1.2f, topY, cx + flicker, topY - h * 0.35f)
            tempPath.quadTo(cx + halfW * 1.2f, topY, cx + halfW * 1.1f, bottomY - h * 0.2f)
            tempPath.close()
            accentPaint.color = Color.argb(180, 255, 193, 7)
            canvas.drawPath(tempPath, accentPaint)
            accentPaint.color = skin.accentColor
        }

        when (skin.shapeType) {
            2 -> {
                tempRect.set(cx - halfW * 1.08f, topY + h * 0.14f, cx + halfW * 1.08f, bottomY)
                canvas.drawRoundRect(tempRect, halfW * 0.45f, halfW * 0.45f, bodyPaint)
                accentPaint.color = skin.accentColor
                canvas.drawRect(cx - halfW * 1.05f, topY + h * 0.32f, cx + halfW * 1.05f, topY + h * 0.40f, accentPaint)
                canvas.drawRect(cx - halfW * 1.05f, bottomY - h * 0.24f, cx + halfW * 1.05f, bottomY - h * 0.16f, accentPaint)
                drawFoamCap(canvas, cx, topY + h * 0.16f, halfW * 0.95f, h * 0.22f)
            }
            3 -> {
                tempRect.set(cx - halfW * 0.82f, topY + h * 0.36f, cx + halfW * 0.82f, bottomY)
                canvas.drawRoundRect(tempRect, halfW * 0.28f, halfW * 0.28f, bodyPaint)
                canvas.drawRect(cx - halfW * 0.32f, topY + h * 0.05f, cx + halfW * 0.32f, topY + h * 0.40f, bodyPaint)
                canvas.drawRect(cx - halfW * 0.65f, topY + h * 0.52f, cx + halfW * 0.65f, topY + h * 0.78f, accentPaint)
                canvas.drawRect(cx - halfW * 0.36f, topY, cx + halfW * 0.36f, topY + h * 0.08f, foamPaint)
            }
            4 -> {
                tempRect.set(cx - halfW * 0.88f, topY + h * 0.12f, cx + halfW * 0.88f, bottomY)
                canvas.drawRoundRect(tempRect, halfW * 0.22f, halfW * 0.22f, bodyPaint)
                canvas.drawRect(cx - halfW * 0.82f, topY + h * 0.08f, cx + halfW * 0.82f, topY + h * 0.16f, foamPaint)
                canvas.drawRect(cx - halfW * 0.88f, topY + h * 0.40f, cx + halfW * 0.88f, topY + h * 0.65f, accentPaint)
            }
            5 -> {
                tempPath.reset()
                tempPath.moveTo(cx - halfW * 0.68f, bottomY)
                tempPath.lineTo(cx - halfW * 0.55f, topY + h * 0.55f)
                tempPath.lineTo(cx - halfW * 0.92f, topY + h * 0.16f)
                tempPath.lineTo(cx + halfW * 0.92f, topY + h * 0.16f)
                tempPath.lineTo(cx + halfW * 0.55f, topY + h * 0.55f)
                tempPath.lineTo(cx + halfW * 0.68f, bottomY)
                tempPath.close()
                canvas.drawPath(tempPath, bodyPaint)
                drawFoamCap(canvas, cx, topY + h * 0.18f, halfW * 0.96f, h * 0.26f)
            }
            6 -> {
                drawHandle(canvas, cx + halfW * 0.85f, topY + h * 0.30f, halfW * 0.58f, h * 0.48f, skin.accentColor)
                tempRect.set(cx - halfW * 0.88f, topY + h * 0.18f, cx + halfW * 0.88f, bottomY)
                canvas.drawRoundRect(tempRect, halfW * 0.22f, halfW * 0.22f, bodyPaint)
                canvas.drawRect(cx - halfW * 0.88f, topY + h * 0.34f, cx + halfW * 0.88f, topY + h * 0.42f, accentPaint)
                drawFoamCap(canvas, cx, topY + h * 0.20f, halfW * 0.94f, h * 0.24f)
            }
            7 -> {
                canvas.drawRect(cx - halfW * 0.65f, bottomY - h * 0.10f, cx + halfW * 0.65f, bottomY, darkPaint)
                canvas.drawRect(cx - halfW * 0.20f, bottomY - h * 0.36f, cx + halfW * 0.20f, bottomY - h * 0.08f, bodyPaint)
                tempRect.set(cx - halfW * 0.95f, topY + h * 0.14f, cx + halfW * 0.95f, bottomY - h * 0.32f)
                canvas.drawRoundRect(tempRect, halfW * 0.42f, halfW * 0.42f, bodyPaint)
                canvas.drawCircle(cx, topY + h * 0.40f, halfW * 0.22f, accentPaint)
                drawFoamCap(canvas, cx, topY + h * 0.16f, halfW * 0.92f, h * 0.20f)
            }
            else -> {
                drawHandle(canvas, cx + halfW * 0.85f, topY + h * 0.30f, halfW * 0.55f, h * 0.46f, skin.darkColor)
                tempRect.set(cx - halfW * 0.90f, topY + h * 0.18f, cx + halfW * 0.90f, bottomY)
                canvas.drawRoundRect(tempRect, halfW * 0.22f, halfW * 0.22f, bodyPaint)

                tempRect.set(cx - halfW * 0.55f, topY + h * 0.28f, cx - halfW * 0.22f, bottomY - h * 0.10f)
                canvas.drawRoundRect(tempRect, halfW * 0.12f, halfW * 0.12f, darkPaint)

                accentPaint.color = Color.argb(145, 255, 255, 255)
                canvas.drawRect(cx + halfW * 0.38f, topY + h * 0.28f, cx + halfW * 0.56f, bottomY - h * 0.12f, accentPaint)

                drawFoamCap(canvas, cx, topY + h * 0.20f, halfW * 0.96f, h * 0.24f)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, leftX: Float, topY: Float, w: Float, h: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = (w * 0.34f).coerceAtLeast(4f)
        tempRect.set(leftX - w * 0.3f, topY, leftX + w, topY + h)
        canvas.drawArc(tempRect, -90f, 180f, false, strokePaint)
    }

    private fun drawFoamCap(canvas: Canvas, cx: Float, y: Float, halfW: Float, foamH: Float) {
        canvas.drawCircle(cx - halfW * 0.55f, y, foamH * 0.62f, foamPaint)
        canvas.drawCircle(cx, y - foamH * 0.18f, foamH * 0.74f, foamPaint)
        canvas.drawCircle(cx + halfW * 0.55f, y, foamH * 0.62f, foamPaint)
        tempRect.set(cx - halfW * 0.65f, y, cx - halfW * 0.35f, y + foamH * 1.15f)
        canvas.drawRoundRect(tempRect, foamH * 0.25f, foamH * 0.25f, foamPaint)
    }
}
