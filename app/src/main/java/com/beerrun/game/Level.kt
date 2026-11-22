package com.beerrun.game

import java.util.Random

/**
 * Генератор уровней с растущей сложностью, стратегическими парами гейтов и ловушками.
 */
data class LevelData(
    val levelNumber: Int,
    val length: Float,
    val bossThirst: Int,
    val gates: List<GatePair>,
    val traps: List<Trap>
)

object LevelGenerator {
    fun generate(levelNumber: Int): LevelData {
        if (levelNumber == 1) {
            return createLevelOne()
        }

        val rng = Random(levelNumber * 7919L + 1337L)
        val segmentCount = (5 + levelNumber / 2).coerceAtMost(12)
        val spacing = 46f
        val gates = ArrayList<GatePair>(segmentCount)
        val traps = ArrayList<Trap>(segmentCount)

        var simulatedCrowd = Config.START_MUGS

        for (i in 0 until segmentCount) {
            val z = 48f + i * spacing

            // Формируем пару ворот: "плохой против хорошего" или "малый плюс против большого"
            val pair = createStrategicPair(rng, simulatedCrowd, levelNumber)
            gates.add(pair)

            // Оцениваем лучший выбор для расчёта жажды босса
            val bestAfter = maxOf(pair.left.apply(simulatedCrowd), pair.right.apply(simulatedCrowd))
            simulatedCrowd = bestAfter.coerceAtLeast(1)

            // Между воротами ставим шипы или лужи
            if (i < segmentCount - 1) {
                val trapZ = z + spacing * 0.52f
                val lane = when (rng.nextInt(3)) {
                    0 -> -0.52f
                    1 -> 0f
                    else -> 0.52f
                }
                val type = if (rng.nextBoolean()) TrapType.SPIKES else TrapType.PUDDLE
                val radius = if (type == TrapType.SPIKES) 0.24f else 0.30f
                traps.add(Trap(lane, trapZ, radius, type))

                // На высоких уровнях иногда добавляем вторую ловушку сбоку
                if (levelNumber >= 3 && rng.nextFloat() < 0.45f) {
                    val secondLane = if (lane <= 0f) 0.58f else -0.58f
                    traps.add(Trap(secondLane, trapZ + 6f, 0.22f, TrapType.SPIKES))
                }
            }
        }

        val finishZ = 48f + segmentCount * spacing + 28f
        val targetThirst = ((simulatedCrowd * 0.62f).toInt()
            .coerceAtLeast(Config.BASE_BOSS_THIRST + (levelNumber - 1) * Config.BOSS_THIRST_STEP))

        return LevelData(
            levelNumber = levelNumber,
            length = finishZ,
            bossThirst = targetThirst,
            gates = gates,
            traps = traps
        )
    }

    private fun createLevelOne(): LevelData {
        val gates = listOf(
            GatePair(45f, GateOption(GateOp.ADD, 10), GateOption(GateOp.SUB, 20)),
            GatePair(92f, GateOption(GateOp.ADD, 15), GateOption(GateOp.MUL, 2)),
            GatePair(140f, GateOption(GateOp.DIV, 2), GateOption(GateOp.ADD, 35)),
            GatePair(188f, GateOption(GateOp.MUL, 3), GateOption(GateOp.ADD, 25)),
            GatePair(236f, GateOption(GateOp.SUB, 30), GateOption(GateOp.ADD, 50))
        )
        val traps = listOf(
            Trap(0f, 116f, 0.24f, TrapType.SPIKES),
            Trap(-0.48f, 164f, 0.30f, TrapType.PUDDLE),
            Trap(0.45f, 212f, 0.24f, TrapType.SPIKES)
        )
        return LevelData(
            levelNumber = 1,
            length = 285f,
            bossThirst = Config.BASE_BOSS_THIRST,
            gates = gates,
            traps = traps
        )
    }

    private fun createStrategicPair(rng: Random, currentCrowd: Int, level: Int): GatePair {
        val goodOnLeft = rng.nextBoolean()
        val mode = rng.nextInt(3)

        val good: GateOption
        val badOrSmall: GateOption

        when (mode) {
            0 -> {
                // Плохой красный против хорошего голубого
                good = if (currentCrowd >= 15 && rng.nextBoolean()) {
                    GateOption(GateOp.MUL, Config.POSITIVE_MUL_VALUES[rng.nextInt(Config.POSITIVE_MUL_VALUES.size)])
                } else {
                    val addVal = Config.POSITIVE_ADD_VALUES[(rng.nextInt(3) + (level / 3)).coerceAtMost(Config.POSITIVE_ADD_VALUES.lastIndex)]
                    GateOption(GateOp.ADD, addVal)
                }
                badOrSmall = if (currentCrowd >= 25 && rng.nextBoolean()) {
                    GateOption(GateOp.DIV, Config.NEGATIVE_DIV_VALUES[rng.nextInt(Config.NEGATIVE_DIV_VALUES.size)])
                } else {
                    GateOption(GateOp.SUB, Config.NEGATIVE_SUB_VALUES[rng.nextInt(Config.NEGATIVE_SUB_VALUES.size)])
                }
            }
            1 -> {
                // Малый плюс против большого плюса/множителя
                val smallAdd = Config.POSITIVE_ADD_VALUES[rng.nextInt(2)]
                badOrSmall = GateOption(GateOp.ADD, smallAdd)
                good = if (currentCrowd >= 12) {
                    GateOption(GateOp.MUL, Config.POSITIVE_MUL_VALUES[rng.nextInt(Config.POSITIVE_MUL_VALUES.size)])
                } else {
                    GateOption(GateOp.ADD, Config.POSITIVE_ADD_VALUES[3 + rng.nextInt(3)])
                }
            }
            else -> {
                // Крупный плюс против красного вычитания
                good = GateOption(GateOp.ADD, Config.POSITIVE_ADD_VALUES[2 + rng.nextInt(4)])
                badOrSmall = GateOption(GateOp.SUB, Config.NEGATIVE_SUB_VALUES[1 + rng.nextInt(3)])
            }
        }

        return if (goodOnLeft) {
            GatePair(0f, good, badOrSmall)
        } else {
            GatePair(0f, badOrSmall, good)
        }
    }
}
