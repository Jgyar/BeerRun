package com.beerrun.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class LevelData(
    val levelNumber: Int,
    val seed: Long,
    val trackLength: Float,
    val location: Config.LocationDef,
    val bossSkin: Config.BossSkinDef,
    val isMegaBoss: Boolean,
    val isFastBoss: Boolean,
    val gateRows: MutableList<GateRow>,
    val obstacles: MutableList<ObstacleItem>,
    val pickups: MutableList<PickupItem>,
    val enemies: MutableList<EnemyGroup>,
    val miniBoss: Boss?,
    val mainBoss: Boss,
    val optimalMaxMugs: Int
)

object LevelGenerator {

    fun generate(levelNumber: Int, prefs: Prefs): LevelData {
        val safeLevel = levelNumber.coerceAtLeast(1)
        val seed = safeLevel * 10007L + 42L
        val rng = Random(seed)

        val location = if (prefs.selectedLocationId == "auto") {
            Config.getLocationForLevel(safeLevel)
        } else {
            Config.getLocationById(prefs.selectedLocationId)
        }

        val bossSkin = if (prefs.selectedBossId == "auto") {
            Config.getBossForLevel(safeLevel)
        } else {
            Config.getBossSkin(prefs.selectedBossId)
        }

        val isMegaBoss = (safeLevel % 5 == 0)
        val isFastBoss = (!isMegaBoss && (safeLevel % 3 == 0) && safeLevel > 1)

        // Длина трассы растёт с уровнем в разумных пределах
        val trackLength = (2300f + min(safeLevel, 25) * 95f).coerceIn(2300f, 4700f)

        // Минимум 6 пар/рядов рамок на каждом уровне, число растёт с уровнем
        val gateRowCount = (6 + min((safeLevel - 1) / 2, 8)).coerceIn(6, 14)

        val gateRows = ArrayList<GateRow>(gateRowCount)
        val obstacles = ArrayList<ObstacleItem>()
        val pickups = ArrayList<PickupItem>()
        val enemies = ArrayList<EnemyGroup>()

        val startZ = 340f
        val endZ = trackLength - 380f
        val stepZ = (endZ - startZ) / gateRowCount.toFloat()

        // Мини-босс посередине трассы начиная с 3-го уровня
        val midRowIdx = if (safeLevel >= 3) gateRowCount / 2 else -1
        var miniBoss: Boss? = null

        for (i in 0 until gateRowCount) {
            val z = startZ + i * stepZ

            if (i == midRowIdx) {
                val miniSkin = Config.BOSS_SKINS[(safeLevel + 2) % Config.BOSS_SKINS.size]
                miniBoss = Boss(
                    z = z,
                    maxThirst = 12 + safeLevel * 2,
                    skin = miniSkin,
                    isMiniBoss = true,
                    isMegaBoss = false,
                    isFastBoss = false
                )
                continue
            }

            val row = createGateRow(safeLevel, i, z, rng)
            gateRows.add(row)

            // Добавляем препятствия, врагов и бонусы между рядами рамок
            val midZ = z + stepZ * 0.50f
            if (midZ < endZ - 120f) {
                populateSegment(safeLevel, i, midZ, rng, obstacles, pickups, enemies)
            }
        }

        // Гарантируем, что рамки всегда есть
        if (gateRows.isEmpty()) {
            gateRows.add(
                GateRow(
                    z = 500f,
                    gates = mutableListOf(
                        GateItem(-0.5f, 0.46f, Config.GateOp.ADD, 10),
                        GateItem(0.5f, 0.46f, Config.GateOp.MUL, 2)
                    )
                )
            )
        }

        // Проверка проходимости по оптимальному пути от базового старта
        var optimalMax = computeOptimalPath(gateRows, miniBoss)
        if (optimalMax < 16) {
            // Страховка: усиливаем первую рамку и пересчитываем
            gateRows[0].gates[0].op = Config.GateOp.ADD
            gateRows[0].gates[0].value = 18
            optimalMax = computeOptimalPath(gateRows, miniBoss)
        }

        // Подгоняем жажду босса под 65%-78% от оптимального пути (не тривиально, но 100% проходимо)
        val ratio = when {
            isMegaBoss -> 0.78f
            isFastBoss -> 0.62f
            else -> 0.68f
        }
        val rawThirst = (optimalMax * ratio).toInt()
        val bossThirst = rawThirst
            .coerceIn(8, min(optimalMax - 2, Config.MAX_MUGS_CAP).coerceAtLeast(8))

        val mainBoss = Boss(
            z = trackLength,
            maxThirst = bossThirst,
            skin = bossSkin,
            isMiniBoss = false,
            isMegaBoss = isMegaBoss,
            isFastBoss = isFastBoss
        )

        return LevelData(
            levelNumber = safeLevel,
            seed = seed,
            trackLength = trackLength,
            location = location,
            bossSkin = bossSkin,
            isMegaBoss = isMegaBoss,
            isFastBoss = isFastBoss,
            gateRows = gateRows,
            obstacles = obstacles,
            pickups = pickups,
            enemies = enemies,
            miniBoss = miniBoss,
            mainBoss = mainBoss,
            optimalMaxMugs = optimalMax
        )
    }

    private fun createGateRow(
        level: Int,
        rowIndex: Int,
        z: Float,
        rng: Random
    ): GateRow {
        // Первый уровень: понятные и приятные рамки для знакомства
        if (level == 1) {
            val gates = when (rowIndex) {
                0 -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.ADD, 6),
                    GateItem(0.50f, 0.46f, Config.GateOp.ADD, 3)
                )
                1 -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.MUL, 2),
                    GateItem(0.50f, 0.46f, Config.GateOp.ADD, 5)
                )
                2 -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.SUB, 4),
                    GateItem(0.50f, 0.46f, Config.GateOp.ADD, 10)
                )
                3 -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.MUL, 2),
                    GateItem(0.50f, 0.46f, Config.GateOp.DIV, 2)
                )
                4 -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.ADD, 12),
                    GateItem(0.50f, 0.46f, Config.GateOp.SUB, 6)
                )
                else -> mutableListOf(
                    GateItem(-0.50f, 0.46f, Config.GateOp.MUL, 2),
                    GateItem(0.50f, 0.46f, Config.GateOp.ADD, 15)
                )
            }
            return GateRow(z = z, gates = gates)
        }

        // Начиная со 2 уровня: двойные или тройные рамки + движущиеся рамки
        val useTriple = (level >= 3 && rowIndex % 3 == 2 && rng.nextFloat() < 0.55f)
        val isMoving = (level >= 2 && rowIndex > 0 && rng.nextFloat() < 0.35f)

        val gates = ArrayList<GateItem>(3)
        if (useTriple) {
            val lanes = floatArrayOf(-0.66f, 0.0f, 0.66f)
            val goodIdx = rng.nextInt(3)
            for (idx in 0 until 3) {
                val g = if (idx == goodIdx) {
                    makeGoodGate(lanes[idx], 0.30f, level, rowIndex, rng)
                } else {
                    makeMixedOrBadGate(lanes[idx], 0.30f, level, rng)
                }
                gates.add(g)
            }
        } else {
            val leftGood = rng.nextBoolean()
            val gLeft = if (leftGood) {
                makeGoodGate(-0.50f, 0.45f, level, rowIndex, rng)
            } else {
                makeMixedOrBadGate(-0.50f, 0.45f, level, rng)
            }
            val gRight = if (!leftGood) {
                makeGoodGate(0.50f, 0.45f, level, rowIndex, rng)
            } else {
                makeMixedOrBadGate(0.50f, 0.45f, level, rng)
            }
            gates.add(gLeft)
            gates.add(gRight)
        }

        return GateRow(
            z = z,
            gates = gates,
            isMoving = isMoving,
            movePhase = rng.nextFloat() * 6.28f,
            moveSpeed = 1.4f + rng.nextFloat() * 0.8f
        )
    }

    private fun makeGoodGate(
        laneX: Float,
        halfW: Float,
        level: Int,
        rowIndex: Int,
        rng: Random
    ): GateItem {
        // В начале уровня чаще + чтобы умножение потом давало эффект
        return if (rowIndex == 0 || rng.nextFloat() < 0.62f) {
            val addVal = (5 + rng.nextInt(6 + min(level, 15) * 2))
            GateItem(laneX, halfW, Config.GateOp.ADD, addVal)
        } else {
            val mulVal = if (level >= 6 && rng.nextFloat() < 0.25f) 3 else 2
            GateItem(laneX, halfW, Config.GateOp.MUL, mulVal)
        }
    }

    private fun makeMixedOrBadGate(
        laneX: Float,
        halfW: Float,
        level: Int,
        rng: Random
    ): GateItem {
        val roll = rng.nextFloat()
        return when {
            roll < 0.30f -> {
                // Слабее, но положительная рамка
                GateItem(laneX, halfW, Config.GateOp.ADD, 2 + rng.nextInt(5))
            }
            roll < 0.72f -> {
                val subVal = 3 + rng.nextInt(4 + min(level, 18))
                GateItem(laneX, halfW, Config.GateOp.SUB, subVal)
            }
            else -> {
                val divVal = if (level >= 5 && rng.nextFloat() < 0.35f) 3 else 2
                GateItem(laneX, halfW, Config.GateOp.DIV, divVal)
            }
        }
    }

    private fun populateSegment(
        level: Int,
        rowIndex: Int,
        z: Float,
        rng: Random,
        obstacles: MutableList<ObstacleItem>,
        pickups: MutableList<PickupItem>,
        enemies: MutableList<EnemyGroup>
    ) {
        val choice = rng.nextInt(6)
        when (choice) {
            0 -> {
                // Вращающаяся пила
                val x = (rng.nextFloat() * 1.2f) - 0.6f
                val moving = level >= 2 && rng.nextBoolean()
                obstacles.add(
                    ObstacleItem(
                        z = z,
                        x = x,
                        halfWidth = 0.22f,
                        type = ObstacleType.SAW,
                        moveSpeed = if (moving) 1.8f else 0f,
                        movePhase = rng.nextFloat() * 6.28f
                    )
                )
            }
            1 -> {
                // Липкая лужа + бонусная кружка сбоку
                val puddleX = if (rng.nextBoolean()) -0.35f else 0.35f
                obstacles.add(
                    ObstacleItem(
                        z = z,
                        x = puddleX,
                        halfWidth = 0.26f,
                        type = ObstacleType.PUDDLE
                    )
                )
                pickups.add(
                    PickupItem(
                        z = z,
                        x = -puddleX * 1.6f,
                        type = PickupType.BONUS_MUG,
                        value = 3 + min(level, 10)
                    )
                )
            }
            2 -> {
                // Ящики-барьеры, которые нужно обойти
                val lane = rng.nextInt(3) - 1 // -1, 0, 1
                obstacles.add(
                    ObstacleItem(
                        z = z,
                        x = lane * 0.55f,
                        halfWidth = 0.25f,
                        type = ObstacleType.CRATE
                    )
                )
            }
            3 -> {
                // Человечки-противники в красном!
                val enemyCount = (3 + min(level * 2, 24) + rowIndex).coerceAtMost(35)
                val x = (rng.nextFloat() * 0.9f) - 0.45f
                enemies.add(
                    EnemyGroup(
                        z = z,
                        x = x,
                        count = enemyCount,
                        initialCount = enemyCount,
                        movePhase = rng.nextFloat() * 6.28f
                    )
                )
            }
            4 -> {
                // Узкий мост (нужно держаться ближе к центру) + сундук с монетами
                if (level >= 2) {
                    obstacles.add(
                        ObstacleItem(
                            z = z,
                            x = 0f,
                            halfWidth = 0.46f, // безопасная ширина моста
                            type = ObstacleType.NARROW_BRIDGE,
                            lengthZ = 150f
                        )
                    )
                    pickups.add(
                        PickupItem(
                            z = z + 40f,
                            x = 0f,
                            type = PickupType.COIN_CHEST,
                            value = 15 + level * 3
                        )
                    )
                }
            }
            else -> {
                // Бонусные кружки по краям дорожки + сундук
                val side = if (rng.nextBoolean()) -0.76f else 0.76f
                pickups.add(
                    PickupItem(
                        z = z,
                        x = side,
                        type = PickupType.BONUS_MUG,
                        value = 4 + min(level, 12)
                    )
                )
                if (rng.nextFloat() < 0.45f) {
                    pickups.add(
                        PickupItem(
                            z = z + 35f,
                            x = -side * 0.85f,
                            type = PickupType.COIN_CHEST,
                            value = 15 + level * 2
                        )
                    )
                }
            }
        }
    }

    private fun computeOptimalPath(gateRows: List<GateRow>, miniBoss: Boss?): Int {
        var current = Config.START_COUNT
        val midZ = miniBoss?.z ?: Float.MAX_VALUE
        var miniBossCounted = false

        for (row in gateRows) {
            if (!miniBossCounted && miniBoss != null && row.z > midZ) {
                current = max(1, current - miniBoss.maxThirst)
                miniBossCounted = true
            }
            var bestInRow = 1
            for (g in row.gates) {
                val candidate = when (g.op) {
                    Config.GateOp.ADD -> current + g.value
                    Config.GateOp.SUB -> current - g.value
                    Config.GateOp.MUL -> current * g.value
                    Config.GateOp.DIV -> if (g.value > 0) current / g.value else current
                }.coerceIn(1, Config.MAX_MUGS_CAP)
                if (candidate > bestInRow) {
                    bestInRow = candidate
                }
            }
            current = bestInRow
        }

        if (!miniBossCounted && miniBoss != null) {
            current = max(1, current - miniBoss.maxThirst)
        }
        return current.coerceIn(1, Config.MAX_MUGS_CAP)
    }
}
