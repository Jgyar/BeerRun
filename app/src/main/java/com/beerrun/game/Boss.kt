package com.beerrun.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Босс в конце уровня: огромный толстый человек в майке с бородой и открытым ртом.
 * Выпивает подбегающие кружки, его живот растёт, а при победе он довольно рыгает.
 */
class Boss {
    var z: Float = 120f
    var maxThirst: Int = Config.BASE_BOSS_THIRST
    var currentThirst: Int = Config.BASE_BOSS_THIRST

    private var idleTime = 0f
    private var gulpTimer = 0f
    private var burpTimer = 0f
    private var roarTimer = 0f
    private var bellyGrowth = 0f

    // Предвыделенные кисти и фигуры
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3E2723")
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#263238")
    }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF6D00")
    }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val burpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(215, 220, 237, 200)
    }

    private val rect = RectF()
    private val path = Path()

    fun reset(finishZ: Float, thirst: Int) {
        z = finishZ
        maxThirst = thirst.coerceAtLeast(10)
        currentThirst = maxThirst
        idleTime = 0f
        gulpTimer = 0f
        burpTimer = 0f
        roarTimer = 0f
        bellyGrowth = 0f
    }

    fun onDrinkMug(amount: Int = 1) {
        if (currentThirst <= 0) return
        currentThirst = (currentThirst - amount).coerceAtLeast(0)
        gulpTimer = 0.22f
        val progress = 1f - (currentThirst.toFloat() / maxThirst.toFloat())
        bellyGrowth = progress * 0.48f
    }

    fun triggerWinBurp() {
        burpTimer = 2.4f
    }

    fun triggerLoseRoar() {
        roarTimer = 2.4f
    }

    fun update(dt: Float) {
        idleTime += dt
        if (gulpTimer > 0f) gulpTimer = max(0f, gulpTimer - dt)
        if (burpTimer > 0f) burpTimer = max(0f, burpTimer - dt)
        if (roarTimer > 0f) roarTimer = max(0f, roarTimer - dt)
    }

    fun getMouthScreenY(horizonY: Float, playerY: Float, roadHalfWidth: Float): Float {
        val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
        val sy = horizonY + (playerY - horizonY) * p
        val unit = roadHalfWidth * p
        return sy - unit * 1.48f
    }

    fun draw(
        canvas: Canvas,
        centerScreenX: Float,
        horizonY: Float,
        playerY: Float,
        roadHalfWidth: Float
    ) {
        if (z < -5f || z > Config.Z_MAX) return

        val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
        val sy = horizonY + (playerY - horizonY) * p
        val unit = roadHalfWidth * p

        val gulpPulse = if (gulpTimer > 0f) sin((gulpTimer / 0.22f) * PI).toFloat() else 0f
        val roarShake = if (roarTimer > 0f) sin(idleTime * 48f) * unit * 0.05f else 0f
        val cx = centerScreenX + roarShake
        val breathe = sin(idleTime * 3.5f) * unit * 0.02f

        // Финишная черта перед боссом
        fillPaint.color = Color.WHITE
        val lineH = (unit * 0.14f).coerceAtLeast(3f)
        canvas.drawRect(cx - unit * 1.02f, sy - lineH * 0.5f, cx + unit * 1.02f, sy + lineH * 0.5f, fillPaint)

        // Ноги / штаны
        fillPaint.color = Color.parseColor("#1565C0")
        rect.set(cx - unit * 0.45f, sy - unit * 0.45f, cx - unit * 0.06f, sy)
        canvas.drawRoundRect(rect, unit * 0.08f, unit * 0.08f, fillPaint)
        rect.set(cx + unit * 0.06f, sy - unit * 0.45f, cx + unit * 0.45f, sy)
        canvas.drawRoundRect(rect, unit * 0.08f, unit * 0.08f, fillPaint)

        // Огромное пузо в белой майке (растёт по мере выпитого пива!)
        val bellyScale = 1f + bellyGrowth + gulpPulse * 0.14f
        val bodyW = unit * 0.62f * bellyScale
        val bodyTop = sy - unit * 1.28f - breathe
        val bodyBottom = sy - unit * 0.32f

        // Руки по бокам
        val armAngleOffset = if (roarTimer > 0f) -unit * 0.25f else sin(idleTime * 4f) * unit * 0.06f
        fillPaint.color = Color.parseColor("#FFCC80")
        rect.set(cx - bodyW - unit * 0.18f, bodyTop + unit * 0.15f + armAngleOffset, cx - bodyW + unit * 0.12f, bodyBottom - unit * 0.05f)
        canvas.drawRoundRect(rect, unit * 0.12f, unit * 0.12f, fillPaint)
        rect.set(cx + bodyW - unit * 0.12f, bodyTop + unit * 0.15f + armAngleOffset, cx + bodyW + unit * 0.18f, bodyBottom - unit * 0.05f)
        canvas.drawRoundRect(rect, unit * 0.12f, unit * 0.12f, fillPaint)

        // Белая майка
        fillPaint.color = Color.parseColor("#FAFAFA")
        rect.set(cx - bodyW, bodyTop, cx + bodyW, bodyBottom)
        canvas.drawRoundRect(rect, bodyW * 0.55f, bodyW * 0.55f, fillPaint)

        // Вырез майки (открытая кожа груди)
        fillPaint.color = Color.parseColor("#FFCC80")
        rect.set(cx - bodyW * 0.34f, bodyTop - unit * 0.02f, cx + bodyW * 0.34f, bodyTop + unit * 0.28f)
        canvas.drawOval(rect, fillPaint)

        // Обводка тела
        strokePaint.strokeWidth = (unit * 0.035f).coerceAtLeast(2f)
        rect.set(cx - bodyW, bodyTop, cx + bodyW, bodyBottom)
        canvas.drawRoundRect(rect, bodyW * 0.55f, bodyW * 0.55f, strokePaint)

        // Голова
        val headR = unit * 0.36f
        val headCy = bodyTop - headR * 0.72f
        fillPaint.color = Color.parseColor("#FFCC80")
        canvas.drawCircle(cx, headCy, headR, fillPaint)
        canvas.drawCircle(cx, headCy, headR, strokePaint)

        // Румяные щёки
        fillPaint.color = Color.argb(140, 239, 83, 80)
        canvas.drawCircle(cx - headR * 0.55f, headCy + headR * 0.08f, headR * 0.22f, fillPaint)
        canvas.drawCircle(cx + headR * 0.55f, headCy + headR * 0.08f, headR * 0.22f, fillPaint)

        // Густая борода
        fillPaint.color = Color.parseColor("#4E342E")
        rect.set(cx - headR * 0.96f, headCy - headR * 0.05f, cx + headR * 0.96f, headCy + headR * 1.18f)
        canvas.drawArc(rect, 0f, 180f, true, fillPaint)

        // Волосы сверху
        rect.set(cx - headR * 0.92f, headCy - headR * 1.05f, cx + headR * 0.92f, headCy - headR * 0.35f)
        canvas.drawArc(rect, 180f, 180f, true, fillPaint)

        // Глаза (довольные дуги при победе, обычные или злые в бою)
        val eyeY = headCy - headR * 0.22f
        if (burpTimer > 0f || currentThirst <= 0) {
            strokePaint.strokeWidth = (unit * 0.035f).coerceAtLeast(2f)
            rect.set(cx - headR * 0.52f, eyeY - headR * 0.14f, cx - headR * 0.16f, eyeY + headR * 0.14f)
            canvas.drawArc(rect, 180f, 180f, false, strokePaint)
            rect.set(cx + headR * 0.16f, eyeY - headR * 0.14f, cx + headR * 0.52f, eyeY + headR * 0.14f)
            canvas.drawArc(rect, 180f, 180f, false, strokePaint)
        } else {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx - headR * 0.34f, eyeY, headR * 0.17f, fillPaint)
            canvas.drawCircle(cx + headR * 0.34f, eyeY, headR * 0.17f, fillPaint)
            fillPaint.color = Color.BLACK
            canvas.drawCircle(cx - headR * 0.34f, eyeY + headR * 0.03f, headR * 0.08f, fillPaint)
            canvas.drawCircle(cx + headR * 0.34f, eyeY + headR * 0.03f, headR * 0.08f, fillPaint)
        }

        // Огромный открытый рот
        val mouthW = headR * (0.48f + gulpPulse * 0.18f)
        val mouthH = headR * (if (gulpTimer > 0f) 0.22f else 0.44f)
        val mouthCy = headCy + headR * 0.42f
        fillPaint.color = Color.parseColor("#880E4F")
        rect.set(cx - mouthW, mouthCy - mouthH, cx + mouthW, mouthCy + mouthH)
        canvas.drawOval(rect, fillPaint)
        // Язык
        fillPaint.color = Color.parseColor("#FF5252")
        rect.set(cx - mouthW * 0.55f, mouthCy, cx + mouthW * 0.55f, mouthCy + mouthH * 0.85f)
        canvas.drawOval(rect, fillPaint)

        // Облачко довольной отрыжки при победе
        if (burpTimer > 0f) {
            val progress = 1f - (burpTimer / 2.4f)
            val cloudX = cx + headR * (0.6f + progress * 1.1f)
            val cloudY = mouthCy - headR * progress * 0.8f
            val r = headR * (0.35f + progress * 0.55f)
            canvas.drawCircle(cloudX, cloudY, r, burpPaint)
            canvas.drawCircle(cloudX + r * 0.65f, cloudY - r * 0.2f, r * 0.75f, burpPaint)
            canvas.drawCircle(cloudX + r * 0.3f, cloudY + r * 0.45f, r * 0.65f, burpPaint)

            val burpTextSize = (unit * 0.26f).coerceIn(16f, 64f)
            textFill.textSize = burpTextSize
            textStroke.textSize = burpTextSize
            textStroke.strokeWidth = burpTextSize * 0.16f
            canvas.drawText("БРРРП!", cloudX + r * 0.3f, cloudY, textStroke)
            canvas.drawText("БРРРП!", cloudX + r * 0.3f, cloudY, textFill)
        }

        // Полоска "жажды" с числом над головой босса
        drawThirstBar(canvas, cx, headCy - headR * 1.45f, unit)
    }

    private fun drawThirstBar(canvas: Canvas, cx: Float, barY: Float, unit: Float) {
        val barW = (unit * 1.45f).coerceIn(90f, 440f)
        val barH = (unit * 0.26f).coerceIn(18f, 52f)
        val left = cx - barW * 0.5f
        val top = barY - barH * 0.5f

        rect.set(left, top, left + barW, top + barH)
        canvas.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, barBgPaint)

        val ratio = (currentThirst.toFloat() / maxThirst.toFloat()).coerceIn(0f, 1f)
        if (ratio > 0f) {
            val fillRight = left + (barW * ratio).coerceAtLeast(barH * 0.6f)
            rect.set(left, top, fillRight.coerceAtMost(left + barW), top + barH)
            canvas.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, barFillPaint)
        }

        strokePaint.strokeWidth = (barH * 0.12f).coerceAtLeast(2f)
        rect.set(left, top, left + barW, top + barH)
        canvas.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, strokePaint)

        val label = if (currentThirst > 0) "ЖАЖДА: $currentThirst" else "НАПИЛСЯ!"
        val ts = barH * 0.72f
        textFill.textSize = ts
        textStroke.textSize = ts
        textStroke.strokeWidth = ts * 0.18f
        val ty = barY + ts * 0.34f
        canvas.drawText(label, cx, ty, textStroke)
        canvas.drawText(label, cx, ty, textFill)
    }
}
