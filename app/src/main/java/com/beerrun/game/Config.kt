package com.beerrun.game

import android.graphics.Color

/**
 * Централизованные константы баланса, перспективы и визуального стиля.
 */
object Config {
    // Баланс толпы и движения
    const val START_MUGS = 1
    const val MAX_VISUAL_MUGS = 220
    const val MAX_PARTICLES = 180
    const val BASE_RUN_SPEED = 24f          // Единиц глубины Z в секунду
    const val SPEED_PER_LEVEL = 0.8f        // Прирост скорости с уровнем
    const val MAX_RUN_SPEED = 36f
    const val STEER_SENSITIVITY = 2.4f      // Чувствительность свайпа влево-вправо
    const val ROAD_HALF_BOUND = 0.82f       // Граница смещения центра толпы [-1..1]

    // Параметры псевдо-3D перспективы
    const val Z_MAX = 105f                  // Дальность прорисовки до горизонта
    const val FOCAL_LENGTH = 25f            // Фокусное расстояние камеры
    const val HORIZON_Y_RATIO = 0.23f       // Положение горизонта по высоте экрана
    const val PLAYER_Y_RATIO = 0.78f        // Положение толпы по высоте экрана
    const val ROAD_HALF_WIDTH_RATIO = 0.43f // Полуширина дороги у нижнего края

    // Баланс гейтов и ловушек
    val POSITIVE_ADD_VALUES = intArrayOf(10, 20, 35, 50, 100, 200)
    val POSITIVE_MUL_VALUES = intArrayOf(2, 3)
    val NEGATIVE_SUB_VALUES = intArrayOf(10, 20, 35, 50)
    val NEGATIVE_DIV_VALUES = intArrayOf(2, 3)

    const val SPIKE_HIT_INTERVAL = 0.055f   // Интервал уничтожения кружки на шипах
    const val PUDDLE_HIT_INTERVAL = 0.075f  // Интервал растворения кружки в луже

    // Баланс босса и наград
    const val BASE_BOSS_THIRST = 110
    const val BOSS_THIRST_STEP = 45
    const val BASE_WIN_COINS = 50
    const val COINS_PER_LEVEL = 15

    // Палитра цветов
    val COLOR_SKY_TOP = Color.parseColor("#1E88E5")
    val COLOR_SKY_BOTTOM = Color.parseColor("#81D4FA")
    val COLOR_GRASS_LIGHT = Color.parseColor("#66BB6A")
    val COLOR_GRASS_DARK = Color.parseColor("#43A047")
    val COLOR_ROAD_BASE = Color.parseColor("#8D6E63")
    val COLOR_ROAD_PLANK = Color.parseColor("#6D4C41")
    val COLOR_CURB_GOLD = Color.parseColor("#FFB300")
    val COLOR_CURB_WHITE = Color.parseColor("#FFF8E1")

    val COLOR_GATE_GOOD = Color.parseColor("#00B0FF")
    val COLOR_GATE_GOOD_BORDER = Color.parseColor("#E1F5FE")
    val COLOR_GATE_BAD = Color.parseColor("#FF1744")
    val COLOR_GATE_BAD_BORDER = Color.parseColor("#FFEBEE")

    val COLOR_BEER_GOLD = Color.parseColor("#FFC107")
    val COLOR_BEER_AMBER = Color.parseColor("#FF8F00")
    val COLOR_FOAM = Color.parseColor("#FFFFFF")
    val COLOR_FOAM_SHADOW = Color.parseColor("#FFF8E1")

    // Скины кружек в магазине
    enum class Skin(val id: Int, val title: String, val price: Int) {
        GLASS_MUG(0, "Кружка", 0),
        OAK_BARREL(1, "Бочонок", 150),
        CRAFT_BOTTLE(2, "Бутылка", 350);

        companion object {
            fun fromId(id: Int): Skin = values().firstOrNull { it.id == id } ?: GLASS_MUG
        }
    }
}
