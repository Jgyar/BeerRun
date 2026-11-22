package com.beerrun.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

enum class GateOp {
    ADD, SUB, MUL, DIV
}

data class GateOption(
    val op: GateOp,
    val value: Int
) {
    val isPositive: Boolean
        get() = op == GateOp.ADD || op == GateOp.MUL

    val label: String
        get() = when (op) {
            GateOp.ADD -> "+$value"
            GateOp.SUB -> "−$value"
            GateOp.MUL -> "×$value"
            GateOp.DIV -> "÷$value"
        }

    fun apply(current: Int): Int = when (op) {
        GateOp.ADD -> current + value
        GateOp.SUB -> (current - value).coerceAtLeast(0)
        GateOp.MUL -> current * value
        GateOp.DIV -> if (value > 0) current / value else current
    }
}

/**
 * Пара рамок-гейтов на всю ширину дорожки (левая и правая).
 */
class GatePair(
    var z: Float,
    val left: GateOption,
    val right: GateOption
) {
    var triggered = false
    var triggeredLeft = false

    fun draw(
        canvas: Canvas,
        centerScreenX: Float,
        horizonY: Float,
        playerY: Float,
        roadHalfWidth: Float,
        paints: WorldPaints
    ) {
        if (z < -2f || z > Config.Z_MAX) return

        val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
        val sy = horizonY + (playerY - horizonY) * p
        val halfW = roadHalfWidth * p
        val gateHeight = roadHalfWidth * 0.56f * p
        val topY = sy - gateHeight

        if (!triggered || !triggeredLeft) {
            drawHalfGate(canvas, centerScreenX - halfW, centerScreenX, topY, sy, p, left, paints)
        }
        if (!triggered || triggeredLeft) {
            drawHalfGate(canvas, centerScreenX, centerScreenX + halfW, topY, sy, p, right, paints)
        }
    }

    private fun drawHalfGate(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        topY: Float,
        bottomY: Float,
        scale: Float,
        option: GateOption,
        paints: WorldPaints
    ) {
        // Полупрозрачное поле рамки
        paints.gateFill.color = if (option.isPositive) {
            Color.argb(150, 0, 176, 255)
        } else {
            Color.argb(150, 255, 23, 68)
        }
        paints.rect.set(leftX, topY, rightX, bottomY)
        canvas.drawRect(paints.rect, paints.gateFill)

        // Верхняя перекладина и стойки
        val postW = (14f * scale).coerceAtLeast(2f)
        paints.gateFrame.color = if (option.isPositive) {
            Config.COLOR_GATE_GOOD_BORDER
        } else {
            Config.COLOR_GATE_BAD_BORDER
        }
        canvas.drawRect(leftX, topY, rightX, topY + postW * 1.1f, paints.gateFrame)
        canvas.drawRect(leftX, topY, leftX + postW, bottomY, paints.gateFrame)
        canvas.drawRect(rightX - postW, topY, rightX, bottomY, paints.gateFrame)

        // Крупная цифра с контрастной обводкой
        val textSize = ((rightX - leftX) * 0.34f).coerceIn(10f, 84f)
        val midX = (leftX + rightX) * 0.5f
        val midY = (topY + bottomY) * 0.5f + textSize * 0.35f

        paints.gateTextStroke.textSize = textSize
        paints.gateTextStroke.strokeWidth = (textSize * 0.18f).coerceAtLeast(2f)
        paints.gateTextFill.textSize = textSize

        canvas.drawText(option.label, midX, midY, paints.gateTextStroke)
        canvas.drawText(option.label, midX, midY, paints.gateTextFill)
    }
}

enum class TrapType {
    SPIKES,
    PUDDLE
}

/**
 * Ловушка на дорожке (вращающиеся шипы или разлитая липкая лужа).
 */
class Trap(
    val x: Float,
    var z: Float,
    val radius: Float,
    val type: TrapType
) {
    var angle = 0f
    var hitCooldown = 0f

    fun update(dt: Float) {
        angle = (angle + dt * 240f) % 360f
        if (hitCooldown > 0f) hitCooldown -= dt
    }

    fun draw(
        canvas: Canvas,
        centerScreenX: Float,
        horizonY: Float,
        playerY: Float,
        roadHalfWidth: Float,
        paints: WorldPaints
    ) {
        if (z < -3f || z > Config.Z_MAX) return

        val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
        val sx = centerScreenX + x * roadHalfWidth * p
        val sy = horizonY + (playerY - horizonY) * p
        val rx = radius * roadHalfWidth * p
        val ry = rx * 0.48f

        if (type == TrapType.PUDDLE) {
            paints.puddlePaint.color = Color.parseColor("#6A1B9A")
            paints.rect.set(sx - rx, sy - ry, sx + rx, sy + ry)
            canvas.drawOval(paints.rect, paints.puddlePaint)

            paints.puddlePaint.color = Color.parseColor("#AB47BC")
            paints.rect.set(sx - rx * 0.65f, sy - ry * 0.55f, sx + rx * 0.55f, sy + ry * 0.45f)
            canvas.drawOval(paints.rect, paints.puddlePaint)
        } else {
            // Основание-валик с шипами
            paints.spikeBasePaint.color = Color.parseColor("#37474F")
            paints.rect.set(sx - rx, sy - ry * 0.6f, sx + rx, sy + ry * 0.6f)
            canvas.drawRoundRect(paints.rect, ry * 0.4f, ry * 0.4f, paints.spikeBasePaint)

            // Вращающиеся зубцы пилы
            val teeth = 8
            paints.path.reset()
            val radOffset = Math.toRadians(angle.toDouble()).toFloat()
            for (i in 0 until teeth) {
                val a1 = radOffset + (i * 2f * Math.PI.toFloat() / teeth)
                val a2 = a1 + (Math.PI.toFloat() / teeth)
                val outerX = sx + cos(a1) * rx * 1.15f
                val outerY = sy - ry * 0.35f + sin(a1) * ry * 1.25f
                val innerX = sx + cos(a2) * rx * 0.55f
                val innerY = sy - ry * 0.35f + sin(a2) * ry * 0.6f
                if (i == 0) paints.path.moveTo(outerX, outerY) else paints.path.lineTo(outerX, outerY)
                paints.path.lineTo(innerX, innerY)
            }
            paints.path.close()
            canvas.drawPath(paints.path, paints.spikeBladePaint)
        }
    }
}

/**
 * Общий набор предвыделенных Paint и Path для отрисовки гейтов и ловушек без аллокаций.
 */
class WorldPaints {
    val rect = RectF()
    val path = Path()

    val gateFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val gateFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val gateTextFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val gateTextStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A237E")
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val puddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val spikeBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val spikeBladePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#ECEFF1")
    }
}
