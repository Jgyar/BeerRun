```kotlin
package com.beerrun.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Управляет толпой пивных кружек, их формацией, анимацией бега, скинами и счётчиком сверху.
 * Использует примитивные массивы без аллокаций в onDraw для стабильных 60 FPS.
 */
class Crowd {
    var count: Int = Config.START_MUGS
        private set

    var centerX: Float = 0f
    var targetX: Float = 0f
    var activeSkin: Config.Skin = Config.Skin.GLASS_MUG

    // Анимация подпрыгивания облачка со счётчиком
    private var badgePopScale: Float = 1f
    private var globalTime: Float = 0f

    // Предвыделенные массивы для визуальных кружек (до MAX_VISUAL_MUGS)
    private val maxVisual = Config.MAX_VISUAL_MUGS
    private val mugX = FloatArray(maxVisual)
    private val mugZ = FloatArray(maxVisual)
    private val slotOffsetX = FloatArray(maxVisual)
    private val slotOffsetZ = FloatArray(maxVisual)
    private val mugScale = FloatArray(maxVisual)
    private val mugPhase = FloatArray(maxVisual)
    private val drawOrder = IntArray(maxVisual)
    private var visualCount = 0

    // Предвыделенные кисти и прямоугольники
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3E2723")
    }
    private val foamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Config.COLOR_FOAM
    }
    private val foamShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Config.COLOR_FOAM_SHADOW
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(115, 255, 255, 255)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(65, 0, 0, 0)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF8F00")
        strokeWidth = 6f
    }
    private val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val countStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val tmpRect = RectF()
    private val badgeRect = RectF()

    init {
        computeSpiralSlots()
        reset(Config.START_MUGS)
    }

    /**
     * Спираль Фибоначчи для плотного и естественного расположения толпы.
     */
    private fun computeSpiralSlots() {
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        slotOffsetX[0] = 0f
        slotOffsetZ[0] = 0f
        for (i in 1 until maxVisual) {
            val r = 0.052f * sqrt(i.toFloat())
            val theta = i * goldenAngle
            slotOffsetX[i] = r * cos(theta)
            slotOffsetZ[i] = r * sin(theta) * 14f
        }
    }

    fun reset(initialCount: Int = Config.START_MUGS) {
        centerX = 0f
        targetX = 0f
        count = initialCount.coerceAtLeast(0)
        visualCount = min(count, maxVisual)
        badgePopScale = 1f
        for (i in 0 until maxVisual) {
            mugX[i] = slotOffsetX[i]
            mugZ[i] = slotOffsetZ[i]
            mugScale[i] = if (i < visualCount) 1f else 0f
            mugPhase[i] = (i * 0.73f) % (2f * PI.toFloat())
            drawOrder[i] = i
        }
    }

    fun setCount(newCount: Int) {
        val clamped = newCount.coerceAtLeast(0)
        if (clamped == count) return
        val oldVisual = visualCount
        count = clamped
        visualCount = min(count, maxVisual)
        badgePopScale = 1.35f

        if (visualCount > oldVisual) {
            for (i in oldVisual until visualCount) {
                mugX[i] = centerX + slotOffsetX[i] * 0.4f
                mugZ[i] = slotOffsetZ[i] * 0.4f
                mugScale[i] = 0.15f
                mugPhase[i] = (i * 0.91f) % (2f * PI.toFloat())
            }
        }
    }

    fun update(dt: Float, isRunning: Boolean) {
        globalTime += dt
        badgePopScale += (1f - badgePopScale) * min(1f, dt * 12f)

        // Ограничиваем смещение центра так, чтобы толпа не выходила за края дороги
        val crowdRadius = if (visualCount > 1) 0.052f * sqrt(visualCount.toFloat()) else 0.05f
        val maxBound = (Config.ROAD_HALF_BOUND - crowdRadius * 0.55f).coerceAtLeast(0.25f)
        targetX = targetX.coerceIn(-maxBound, maxBound)
        centerX += (targetX - centerX) * min(1f, dt * 16f)

        val followSpeed = min(1f, dt * 11f)
        val scaleSpeed = min(1f, dt * 14f)

        for (i in 0 until visualCount) {
            val tx = (centerX + slotOffsetX[i]).coerceIn(-0.94f, 0.94f)
            val tz = slotOffsetZ[i]
            mugX[i] += (tx - mugX[i]) * followSpeed
            mugZ[i] += (tz - mugZ[i]) * followSpeed
            mugScale[i] += (1f - mugScale[i]) * scaleSpeed
            if (isRunning) {
                mugPhase[i] += dt * 13f
            }
            drawOrder[i] = i
        }

        // Сортировка вставками от дальних Z к ближним для корректного перекрытия
        for (i in 1 until visualCount) {
            val key = drawOrder[i]
            val keyZ = mugZ[key]
            var j = i - 1
            while (j >= 0 && mugZ[drawOrder[j]] < keyZ) {
                drawOrder[j + 1] = drawOrder[j]
                j--
            }
            drawOrder[j + 1] = key
        }
    }

    /**
     * Возвращает X-координату визуальной кружки по индексу (для проверки столкновения с ловушками).
     */
    fun getVisualMugX(index: Int): Float = mugX[index]
    fun getVisualMugZ(index: Int): Float = mugZ[index]
    fun getVisualCount(): Int = visualCount

    fun draw(
        canvas: Canvas,
        centerScreenX: Float,
        horizonY: Float,
        playerY: Float,
        roadHalfWidth: Float
    ) {
        if (count <= 0 || visualCount <= 0) return

        var minScreenY = Float.MAX_VALUE
        var sumScreenX = 0f

        for (k in 0 until visualCount) {
            val idx = drawOrder[k]
            val z = mugZ[idx]
            val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
            val sx = centerScreenX + mugX[idx] * roadHalfWidth * p
            val baseSy = horizonY + (playerY - horizonY) * p
            val hop = kotlin.math.abs(sin(mugPhase[idx])) * 11f * p
            val sy = baseSy - hop
            val unitSize = roadHalfWidth * 0.135f * p * mugScale[idx]

            sumScreenX += sx
            val topY = sy - unitSize * 2.2f
            if (topY < minScreenY) minScreenY = topY

            drawSingleUnit(canvas, sx, sy, baseSy, unitSize, activeSkin)
        }

        // Облачко со счётчиком количества над толпой
        val avgX = sumScreenX / visualCount
        drawCounterBubble(canvas, avgX, minScreenY - 18f, roadHalfWidth)
    }

    fun drawSingleUnit(
        canvas: Canvas,
        sx: Float,
        sy: Float,
        shadowY: Float,
        size: Float,
        skin: Config.Skin
    ) {
        if (size <= 1f) return

        // Тень на дороге
        tmpRect.set(sx - size * 0.85f, shadowY - size * 0.25f, sx + size * 0.85f, shadowY + size * 0.28f)
        canvas.drawOval(tmpRect, shadowPaint)

        when (skin) {
            Config.Skin.GLASS_MUG -> drawGlassMug(canvas, sx, sy, size)
            Config.Skin.OAK_BARREL -> drawOakBarrel(canvas, sx, sy, size)
            Config.Skin.CRAFT_BOTTLE -> drawCraftBottle(canvas, sx, sy, size)
        }
    }

    private fun drawGlassMug(canvas: Canvas, sx: Float, sy: Float, size: Float) {
        val w = size * 0.72f
        val h = size * 1.65f
        val top = sy - h

        // Ручка кружки справа
        strokePaint.strokeWidth = (size * 0.22f).coerceAtLeast(2f)
        strokePaint.color = Color.parseColor("#E0F7FA")
        tmpRect.set(sx + w * 0.55f, top + h * 0.25f, sx + w * 1.25f, sy - h * 0.22f)
        canvas.drawArc(tmpRect, -90f, 180f, false, strokePaint)

        // Тело кружки (янтарное пиво)
        bodyPaint.color = Config.COLOR_BEER_GOLD
        tmpRect.set(sx - w, top, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.2f, size * 0.2f, bodyPaint)

        // Тёмно-янтарная тень справа
        bodyPaint.color = Config.COLOR_BEER_AMBER
        tmpRect.set(sx + w * 0.15f, top + h * 0.15f, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.15f, size * 0.15f, bodyPaint)

        // Блик на стекле слева
        tmpRect.set(sx - w * 0.68f, top + h * 0.22f, sx - w * 0.35f, sy - h * 0.15f)
        canvas.drawRoundRect(tmpRect, size * 0.1f, size * 0.1f, highlightPaint)

        // Обводка кружки
        strokePaint.strokeWidth = (size * 0.1f).coerceAtLeast(1.5f)
        strokePaint.color = Color.parseColor("#4E342E")
        tmpRect.set(sx - w, top, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.2f, size * 0.2f, strokePaint)

        // Пышная белая пена сверху
        canvas.drawCircle(sx - w * 0.52f, top + size * 0.05f, size * 0.44f, foamShadowPaint)
        canvas.drawCircle(sx + w * 0.52f, top + size * 0.05f, size * 0.44f, foamShadowPaint)
        canvas.drawCircle(sx, top - size * 0.12f, size * 0.54f, foamPaint)
        canvas.drawCircle(sx - w * 0.42f, top - size * 0.02f, size * 0.38f, foamPaint)
        canvas.drawCircle(sx + w * 0.42f, top - size * 0.02f, size * 0.38f, foamPaint)
    }

    private fun drawOakBarrel(canvas: Canvas, sx: Float, sy: Float, size: Float) {
        val w = size * 0.85f
        val h = size * 1.65f
        val top = sy - h

        // Пузатый дубовый бочонок
        bodyPaint.color = Color.parseColor("#795548")
        tmpRect.set(sx - w, top, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.45f, size * 0.45f, bodyPaint)

        // Тень справа
        bodyPaint.color = Color.parseColor("#5D4037")
        tmpRect.set(sx + w * 0.1f, top + size * 0.1f, sx + w, sy - size * 0.05f)
        canvas.drawRoundRect(tmpRect, size * 0.35f, size * 0.35f, bodyPaint)

        // Металлические обручи
        bodyPaint.color = Color.parseColor("#37474F")
        canvas.drawRect(sx - w * 0.94f, top + h * 0.26f, sx + w * 0.94f, top + h * 0.38f, bodyPaint)
        canvas.drawRect(sx - w * 0.94f, top + h * 0.68f, sx + w * 0.94f, top + h * 0.80f, bodyPaint)

        // Обводка
        strokePaint.strokeWidth = (size * 0.1f).coerceAtLeast(1.5f)
        strokePaint.color = Color.parseColor("#261815")
        tmpRect.set(sx - w, top, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.45f, size * 0.45f, strokePaint)

        // Шапка пены вылезает сверху
        canvas.drawCircle(sx - w * 0.38f, top, size * 0.36f, foamPaint)
        canvas.drawCircle(sx + w * 0.38f, top, size * 0.36f, foamPaint)
        canvas.drawCircle(sx, top - size * 0.12f, size * 0.46f, foamPaint)
    }

    private fun drawCraftBottle(canvas: Canvas, sx: Float, sy: Float, size: Float) {
        val w = size * 0.62f
        val h = size * 1.95f
        val top = sy - h
        val neckW = size * 0.26f
        val neckBottom = top + h * 0.38f

        // Основной корпус бутылки (изумрудное стекло)
        bodyPaint.color = Color.parseColor("#2E7D32")
        tmpRect.set(sx - w, neckBottom - size * 0.15f, sx + w, sy)
        canvas.drawRoundRect(tmpRect, size * 0.28f, size * 0.22f, bodyPaint)

        // Горлышко
        tmpRect.set(sx - neckW, top + size * 0.15f, sx + neckW, neckBottom + size * 0.1f)
        canvas.drawRoundRect(tmpRect, size * 0.1f, size * 0.1f, bodyPaint)

        // Золотая этикетка в центре
        bodyPaint.color = Config.COLOR_BEER_GOLD
        tmpRect.set(sx - w * 0.78f, top + h * 0.52f, sx + w * 0.78f, top + h * 0.80f)
        canvas.drawRoundRect(tmpRect, size * 0.08f, size * 0.08f, bodyPaint)

        // Блик
        tmpRect.set(sx - w * 0.65f, neckBottom + size * 0.05f, sx - w * 0.4f, sy - size * 0.15f)
        canvas.drawRoundRect(tmpRect, size * 0.08f, size * 0.08f, highlightPaint)

        // Пенный фонтанчик из горлышка
        canvas.drawCircle(sx - neckW * 0.7f, top + size * 0.14f, size * 0.28f, foamPaint)
        canvas.drawCircle(sx + neckW * 0.7f, top + size * 0.14f, size * 0.28f, foamPaint)
        canvas.drawCircle(sx, top + size * 0.02f, size * 0.34f, foamPaint)
    }

    private fun drawCounterBubble(canvas: Canvas, cx: Float, cy: Float, roadHalfWidth: Float) {
        val text = count.toString()
        val textSize = (roadHalfWidth * 0.14f * badgePopScale).coerceIn(28f, 68f)
        countTextPaint.textSize = textSize
        countStrokePaint.textSize = textSize
        countStrokePaint.strokeWidth = textSize * 0.16f

        val textWidth = countTextPaint.measureText(text)
        val pillHalfW = (textWidth * 0.68f + textSize * 0.55f).coerceAtLeast(textSize * 1.1f)
        val pillHalfH = textSize * 0.68f

        badgeRect.set(cx - pillHalfW, cy - pillHalfH, cx + pillHalfW, cy + pillHalfH)
        canvas.drawRoundRect(badgeRect, pillHalfH, pillHalfH, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, pillHalfH, pillHalfH, badgeBorderPaint)

        val textY = cy + textSize * 0.35f
        canvas.drawText(text, cx, textY, countStrokePaint)
        canvas.drawText(text, cx, textY, countTextPaint)
    }
}
```
