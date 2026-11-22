package com.beerrun.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sin

class Boss(
    var z: Float,
    var maxThirst: Int,
    val skin: Config.BossSkinDef,
    val isMiniBoss: Boolean = false,
    val isMegaBoss: Boolean = false,
    val isFastBoss: Boolean = false
) {
    var currentThirst: Int = maxThirst
    var bellyScale: Float = 1.0f
    var mouthOpen: Float = 0.0f
    var burpTimer: Float = 0.0f
    var defeated: Boolean = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 30, 30, 35)
    }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 160, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tempRect = RectF()
    private val tempPath = Path()

    fun reset() {
        currentThirst = maxThirst
        bellyScale = 1.0f
        mouthOpen = 0.0f
        burpTimer = 0.0f
        defeated = false
    }

    fun gulpOne(): Boolean {
        if (defeated) return true
        currentThirst = (currentThirst - 1).coerceAtLeast(0)
        val drankRatio = 1f - (currentThirst.toFloat() / max(1, maxThirst))
        bellyScale = 1.0f + drankRatio * 0.62f
        mouthOpen = 1.0f
        if (currentThirst <= 0) {
            defeated = true
            burpTimer = 1.6f
            return true
        }
        return false
    }

    fun update(dt: Float) {
        if (mouthOpen > 0f) {
            mouthOpen = (mouthOpen - dt * 5f).coerceAtLeast(0f)
        }
        if (burpTimer > 0f) {
            burpTimer = (burpTimer - dt).coerceAtLeast(0f)
        }
    }

    fun draw(canvas: Canvas, cx: Float, bottomY: Float, scalePx: Float, timeSec: Float) {
        val sizeMul = when {
            isMiniBoss -> 0.72f
            isMegaBoss -> 1.32f
            else -> 1.0f
        }
        val baseR = scalePx * 0.24f * sizeMul
        val idleBob = sin(timeSec * 4.5f) * (baseR * 0.05f)
        val burpShake = if (burpTimer > 0f) sin(timeSec * 45f) * (baseR * 0.07f) else 0f

        val bodyCx = cx + burpShake
        val bellyR = baseR * bellyScale
        val bellyCy = bottomY - bellyR * 0.92f + idleBob
        val headR = baseR * 0.56f
        val headCy = bellyCy - bellyR * 0.85f - headR * 0.55f

        // Mega-boss aura
        if (isMegaBoss) {
            fillPaint.color = Color.argb(65, 255, 215, 0)
            canvas.drawCircle(bodyCx, bellyCy, bellyR * 1.38f, fillPaint)
        }

        // Legs
        fillPaint.color = Color.rgb(45, 52, 64)
        canvas.drawRect(bodyCx - baseR * 0.45f, bottomY - baseR * 0.45f, bodyCx - baseR * 0.14f, bottomY, fillPaint)
        canvas.drawRect(bodyCx + baseR * 0.14f, bottomY - baseR * 0.45f, bodyCx + baseR * 0.45f, bottomY, fillPaint)

        // Arms
        fillPaint.color = skin.skinColor
        val armAngle = if (burpTimer > 0f) -25f else sin(timeSec * 4f) * 8f
        canvas.save()
        canvas.rotate(armAngle, bodyCx - bellyR * 0.85f, bellyCy - bellyR * 0.3f)
        tempRect.set(
            bodyCx - bellyR * 1.25f,
            bellyCy - bellyR * 0.35f,
            bodyCx - bellyR * 0.75f,
            bellyCy + bellyR * 0.45f
        )
        canvas.drawRoundRect(tempRect, baseR * 0.18f, baseR * 0.18f, fillPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(-armAngle, bodyCx + bellyR * 0.85f, bellyCy - bellyR * 0.3f)
        tempRect.set(
            bodyCx + bellyR * 0.75f,
            bellyCy - bellyR * 0.35f,
            bodyCx + bellyR * 1.25f,
            bellyCy + bellyR * 0.45f
        )
        canvas.drawRoundRect(tempRect, baseR * 0.18f, baseR * 0.18f, fillPaint)
        canvas.restore()

        // Torso / Belly
        fillPaint.color = skin.outfitColor
        canvas.drawCircle(bodyCx, bellyCy, bellyR, fillPaint)

        // Outfit details
        when (skin.styleType) {
            0 -> {
                // Tank top straps
                fillPaint.color = skin.skinColor
                canvas.drawCircle(bodyCx - bellyR * 0.48f, bellyCy - bellyR * 0.65f, bellyR * 0.26f, fillPaint)
                canvas.drawCircle(bodyCx + bellyR * 0.48f, bellyCy - bellyR * 0.65f, bellyR * 0.26f, fillPaint)
            }
            2 -> {
                // Cowboy sheriff star & belt
                fillPaint.color = skin.accentColor
                canvas.drawCircle(bodyCx + bellyR * 0.35f, bellyCy - bellyR * 0.35f, baseR * 0.14f, fillPaint)
            }
            3 -> {
                // Biker zipper & belt
                strokePaint.color = skin.accentColor
                strokePaint.strokeWidth = 4f
                canvas.drawLine(bodyCx, bellyCy - bellyR * 0.85f, bodyCx, bellyCy + bellyR * 0.85f, strokePaint)
            }
            4 -> {
                // King ermine trim
                fillPaint.color = Color.WHITE
                canvas.drawRect(
                    bodyCx - bellyR * 0.22f,
                    bellyCy - bellyR * 0.85f,
                    bodyCx + bellyR * 0.22f,
                    bellyCy + bellyR * 0.85f,
                    fillPaint
                )
            }
            5 -> {
                // Pirate horizontal stripes
                fillPaint.color = Color.WHITE
                canvas.drawRect(bodyCx - bellyR * 0.9f, bellyCy - bellyR * 0.12f, bodyCx + bellyR * 0.9f, bellyCy + bellyR * 0.12f, fillPaint)
            }
            6 -> {
                // Robot chest gauge
                fillPaint.color = skin.accentColor
                tempRect.set(bodyCx - bellyR * 0.45f, bellyCy - bellyR * 0.35f, bodyCx + bellyR * 0.45f, bellyCy + bellyR * 0.25f)
                canvas.drawRoundRect(tempRect, 12f, 12f, fillPaint)
            }
        }

        // Head
        fillPaint.color = skin.skinColor
        canvas.drawCircle(bodyCx, headCy, headR, fillPaint)

        // Beard (for bearded styles)
        if (skin.styleType in intArrayOf(0, 1, 2, 3, 4)) {
            fillPaint.color = skin.beardColor
            tempRect.set(bodyCx - headR * 0.85f, headCy + headR * 0.05f, bodyCx + headR * 0.85f, headCy + headR * 1.15f)
            canvas.drawArc(tempRect, 0f, 180f, true, fillPaint)
        }

        // Eyes
        if (skin.styleType == 3) {
            // Biker sunglasses
            fillPaint.color = Color.BLACK
            canvas.drawRect(bodyCx - headR * 0.68f, headCy - headR * 0.28f, bodyCx - headR * 0.08f, headCy + headR * 0.04f, fillPaint)
            canvas.drawRect(bodyCx + headR * 0.08f, headCy - headR * 0.28f, bodyCx + headR * 0.68f, headCy + headR * 0.04f, fillPaint)
        } else if (skin.styleType == 6) {
            // Robot glowing visor
            fillPaint.color = skin.accentColor
            tempRect.set(bodyCx - headR * 0.68f, headCy - headR * 0.26f, bodyCx + headR * 0.68f, headCy + headR * 0.04f)
            canvas.drawRoundRect(tempRect, 8f, 8f, fillPaint)
        } else {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(bodyCx - headR * 0.34f, headCy - headR * 0.15f, headR * 0.18f, fillPaint)
            canvas.drawCircle(bodyCx + headR * 0.34f, headCy - headR * 0.15f, headR * 0.18f, fillPaint)
            fillPaint.color = Color.BLACK
            canvas.drawCircle(bodyCx - headR * 0.34f, headCy - headR * 0.15f, headR * 0.09f, fillPaint)
            canvas.drawCircle(bodyCx + headR * 0.34f, headCy - headR * 0.15f, headR * 0.09f, fillPaint)

            // Pirate eyepatch
            if (skin.styleType == 5) {
                canvas.drawCircle(bodyCx + headR * 0.34f, headCy - headR * 0.15f, headR * 0.22f, fillPaint)
            }
        }

        // Mouth (gulping / burping)
        val mouthR = headR * (0.20f + mouthOpen * 0.24f + if (burpTimer > 0f) 0.26f else 0f)
        fillPaint.color = Color.rgb(120, 20, 30)
        canvas.drawCircle(bodyCx, headCy + headR * 0.42f, mouthR, fillPaint)

        // Headgear per Boss Skin
        drawBossHat(canvas, bodyCx, headCy, headR)

        // Burp / Victory cloud effect
        if (burpTimer > 0f) {
            val alpha = ((burpTimer / 1.6f) * 210).toInt().coerceIn(0, 210)
            fillPaint.color = if (skin.styleType == 4) {
                Color.argb(alpha, 255, 215, 0)
            } else if (skin.styleType == 6) {
                Color.argb(alpha, 0, 229, 255)
            } else {
                Color.argb(alpha, 139, 195, 74)
            }
            val spread = (1.6f - burpTimer) * baseR * 1.1f
            canvas.drawCircle(bodyCx - spread * 0.4f, headCy + headR * 0.5f - spread * 0.5f, baseR * 0.28f, fillPaint)
            canvas.drawCircle(bodyCx + spread * 0.5f, headCy + headR * 0.4f - spread * 0.4f, baseR * 0.34f, fillPaint)
        }

        // Overhead Thirst Progress Bar
        val barW = (baseR * 2.5f).coerceIn(130f, 260f)
        val barH = 26f
        val barTop = headCy - headR * 1.75f - barH
        tempRect.set(bodyCx - barW * 0.5f, barTop, bodyCx + barW * 0.5f, barTop + barH)
        canvas.drawRoundRect(tempRect, barH * 0.5f, barH * 0.5f, barBgPaint)

        val ratio = (currentThirst.toFloat() / max(1, maxThirst)).coerceIn(0f, 1f)
        barFillPaint.color = when {
            isMegaBoss -> Color.rgb(255, 64, 129)
            isFastBoss -> Color.rgb(0, 229, 255)
            isMiniBoss -> Color.rgb(255, 112, 67)
            else -> Color.rgb(255, 179, 0)
        }
        if (ratio > 0f) {
            tempRect.set(
                bodyCx - barW * 0.5f + 3f,
                barTop + 3f,
                bodyCx - barW * 0.5f + 3f + (barW - 6f) * ratio,
                barTop + barH - 3f
            )
            canvas.drawRoundRect(tempRect, barH * 0.4f, barH * 0.4f, barFillPaint)
        }

        textPaint.textSize = 20f
        val titlePrefix = when {
            isMegaBoss -> "МЕГА-БОСС: "
            isFastBoss -> "БЫСТРЫЙ: "
            isMiniBoss -> "СТРАЖ: "
            else -> ""
        }
        canvas.drawText("$titlePrefix$currentThirst", bodyCx, barTop + barH - 6f, textPaint)
    }

    private fun drawBossHat(canvas: Canvas, cx: Float, headCy: Float, headR: Float) {
        when (skin.styleType) {
            1 -> {
                // Viking helmet + horns
                fillPaint.color = skin.accentColor
                tempRect.set(cx - headR * 0.95f, headCy - headR * 1.05f, cx + headR * 0.95f, headCy - headR * 0.15f)
                canvas.drawArc(tempRect, 180f, 180f, true, fillPaint)
                fillPaint.color = Color.rgb(255, 248, 225)
                tempPath.reset()
                tempPath.moveTo(cx - headR * 0.85f, headCy - headR * 0.45f)
                tempPath.lineTo(cx - headR * 1.45f, headCy - headR * 1.15f)
                tempPath.lineTo(cx - headR * 0.65f, headCy - headR * 0.72f)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)

                tempPath.reset()
                tempPath.moveTo(cx + headR * 0.85f, headCy - headR * 0.45f)
                tempPath.lineTo(cx + headR * 1.45f, headCy - headR * 1.15f)
                tempPath.lineTo(cx + headR * 0.65f, headCy - headR * 0.72f)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            2 -> {
                // Cowboy hat
                fillPaint.color = skin.outfitColor
                tempRect.set(cx - headR * 1.45f, headCy - headR * 0.62f, cx + headR * 1.45f, headCy - headR * 0.36f)
                canvas.drawRoundRect(tempRect, 10f, 10f, fillPaint)
                tempRect.set(cx - headR * 0.75f, headCy - headR * 1.18f, cx + headR * 0.75f, headCy - headR * 0.45f)
                canvas.drawRoundRect(tempRect, 16f, 16f, fillPaint)
            }
            3 -> {
                // Biker bandana
                fillPaint.color = skin.accentColor
                canvas.drawRect(cx - headR * 0.90f, headCy - headR * 0.68f, cx + headR * 0.90f, headCy - headR * 0.38f, fillPaint)
            }
            4 -> {
                // Royal Crown
                fillPaint.color = skin.accentColor
                tempPath.reset()
                tempPath.moveTo(cx - headR * 0.80f, headCy - headR * 0.55f)
                tempPath.lineTo(cx - headR * 0.85f, headCy - headR * 1.25f)
                tempPath.lineTo(cx - headR * 0.35f, headCy - headR * 0.85f)
                tempPath.lineTo(cx, headCy - headR * 1.35f)
                tempPath.lineTo(cx + headR * 0.35f, headCy - headR * 0.85f)
                tempPath.lineTo(cx + headR * 0.85f, headCy - headR * 1.25f)
                tempPath.lineTo(cx + headR * 0.80f, headCy - headR * 0.55f)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            5 -> {
                // Pirate tricorn hat
                fillPaint.color = Color.rgb(33, 33, 33)
                tempPath.reset()
                tempPath.moveTo(cx - headR * 1.35f, headCy - headR * 0.45f)
                tempPath.lineTo(cx, headCy - headR * 1.25f)
                tempPath.lineTo(cx + headR * 1.35f, headCy - headR * 0.45f)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            6 -> {
                // Robot antenna
                strokePaint.color = skin.accentColor
                strokePaint.strokeWidth = 5f
                canvas.drawLine(cx, headCy - headR, cx, headCy - headR * 1.48f, strokePaint)
                fillPaint.color = Color.rgb(255, 64, 129)
                canvas.drawCircle(cx, headCy - headR * 1.52f, headR * 0.18f, fillPaint)
            }
        }
    }
}
