package com.beerrun.game

import kotlin.math.sin

class GateItem(
    var laneCenter: Float,
    var halfWidth: Float,
    var op: Config.GateOp,
    var value: Int,
    var triggered: Boolean = false
) {
    fun isPositive(): Boolean {
        return op == Config.GateOp.ADD || op == Config.GateOp.MUL
    }

    fun getEffectiveValue(happyHourActive: Boolean): Int {
        if (!happyHourActive) return value
        return when (op) {
            Config.GateOp.ADD -> value + 10
            Config.GateOp.MUL -> value + 1
            else -> value
        }
    }

    fun formatLabel(happyHourActive: Boolean): String {
        val v = getEffectiveValue(happyHourActive)
        return when (op) {
            Config.GateOp.ADD -> "+$v"
            Config.GateOp.SUB -> "-$v"
            Config.GateOp.MUL -> "x$v"
            Config.GateOp.DIV -> "÷$v"
        }
    }
}

class GateRow(
    var z: Float,
    val gates: MutableList<GateItem>,
    var isMoving: Boolean = false,
    var movePhase: Float = 0f,
    var moveSpeed: Float = 1.6f,
    var triggered: Boolean = false
) {
    fun getOffsetX(timeSec: Float): Float {
        if (!isMoving) return 0f
        return sin(timeSec * moveSpeed + movePhase) * 0.22f
    }
}

enum class ObstacleType {
    SAW,
    PUDDLE,
    CRATE,
    NARROW_BRIDGE
}

class ObstacleItem(
    var z: Float,
    var x: Float,
    var halfWidth: Float,
    val type: ObstacleType,
    var lengthZ: Float = 40f,
    var moveSpeed: Float = 0f,
    var movePhase: Float = 0f,
    var triggered: Boolean = false,
    var hitTimer: Float = 0f
) {
    fun currentX(timeSec: Float): Float {
        if (moveSpeed == 0f) return x
        return (x + sin(timeSec * moveSpeed + movePhase) * 0.45f).coerceIn(-0.82f, 0.82f)
    }
}

enum class PickupType {
    BONUS_MUG,
    COIN_CHEST
}

class PickupItem(
    var z: Float,
    var x: Float,
    val type: PickupType,
    val value: Int,
    var collected: Boolean = false
)

class EnemyGroup(
    var z: Float,
    var x: Float,
    var count: Int,
    val initialCount: Int,
    var moveSpeed: Float = 0.65f,
    var movePhase: Float = 0f,
    var defeated: Boolean = false
) {
    fun currentX(timeSec: Float): Float {
        return (x + sin(timeSec * moveSpeed + movePhase) * 0.35f).coerceIn(-0.78f, 0.78f)
    }
}
