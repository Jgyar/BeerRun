package com.beerrun.game

import android.graphics.Color

object Config {
    const val START_COUNT = 1
    const val MAX_MUGS_CAP = 5000

    // Комфортная скорость бега, чтобы успевать читать рамки и реагировать
    const val BASE_RUN_SPEED = 265f
    const val TRACK_HALF_WIDTH = 1.0f
    const val NORMAL_BOSS_GULP_INTERVAL = 0.062f
    const val FAST_BOSS_GULP_INTERVAL = 0.034f

    const val HAPPY_HOUR_DURATION = 12f
    const val START_PACK_BONUS = 20
    const val MAX_UPGRADE_LEVEL = 10

    enum class GateOp {
        ADD, SUB, MUL, DIV
    }

    enum class BoosterType(
        val id: String,
        val title: String,
        val desc: String,
        val price: Int,
        val color: Int
    ) {
        START_PACK(
            "start_pack",
            "Стартовая пачка",
            "Старт уровня с +20 кружками",
            120,
            Color.rgb(255, 179, 0)
        ),
        MAGNET(
            "magnet",
            "Магнит",
            "Притягивает бонусные кружки и сундуки",
            150,
            Color.rgb(229, 57, 53)
        ),
        FOAM_SHIELD(
            "foam_shield",
            "Щит из пены",
            "Защита от первой ловушки или врагов",
            140,
            Color.rgb(41, 182, 246)
        ),
        DOUBLE_COINS(
            "double_coins",
            "Золотой множитель",
            "x2 монет за прохождение уровня",
            180,
            Color.rgb(255, 213, 79)
        ),
        ICE_KEG(
            "ice_keg",
            "Ледяной бочонок",
            "Первая плохая рамка станет +5",
            160,
            Color.rgb(128, 222, 234)
        ),
        HAPPY_HOUR(
            "happy_hour",
            "Счастливый час",
            "Бонус ко всем + и x рамкам на 12 сек",
            200,
            Color.rgb(171, 71, 188)
        )
    }

    enum class UpgradeType(
        val id: String,
        val title: String,
        val desc: String,
        val basePrice: Int,
        val maxLevel: Int
    ) {
        COIN_INCOME(
            "coin_income",
            "Доход монет +10%",
            "Увеличивает награду монет за уровни",
            250,
            MAX_UPGRADE_LEVEL
        ),
        START_MUGS(
            "start_mugs",
            "Кружки на старте +1",
            "Больше кружек в начале каждого забега",
            300,
            MAX_UPGRADE_LEVEL
        )
    }

    data class MugSkinDef(
        val id: String,
        val name: String,
        val price: Int,
        val bodyColor: Int,
        val darkColor: Int,
        val foamColor: Int,
        val accentColor: Int,
        val shapeType: Int,
        val unlockAchievementId: String? = null
    )

    data class BossSkinDef(
        val id: String,
        val name: String,
        val price: Int,
        val skinColor: Int,
        val outfitColor: Int,
        val beardColor: Int,
        val accentColor: Int,
        val styleType: Int
    )

    data class LocationDef(
        val id: String,
        val name: String,
        val price: Int,
        val skyTop: Int,
        val skyBottom: Int,
        val trackMain: Int,
        val trackAlt: Int,
        val railColor: Int,
        val sideColor: Int,
        val decorType: Int
    )

    data class AchievementDef(
        val id: String,
        val title: String,
        val desc: String,
        val target: Int,
        val rewardCoins: Int,
        val statKey: String,
        val rewardSkinId: String? = null
    )

    // 12 скинов кружек
    val MUG_SKINS: List<MugSkinDef> = listOf(
        MugSkinDef(
            "classic_pint", "Обычная пинта", 0,
            Color.rgb(245, 166, 35), Color.rgb(198, 118, 12),
            Color.rgb(255, 253, 245), Color.rgb(255, 224, 130), 0
        ),
        MugSkinDef(
            "glass_mug", "Стеклянная кружка", 150,
            Color.rgb(255, 193, 7), Color.rgb(214, 137, 16),
            Color.rgb(255, 255, 255), Color.rgb(179, 229, 252), 1
        ),
        MugSkinDef(
            "barrel", "Пивной бочонок", 300,
            Color.rgb(141, 85, 36), Color.rgb(93, 53, 20),
            Color.rgb(255, 248, 225), Color.rgb(176, 190, 197), 2
        ),
        MugSkinDef(
            "bottle", "Бутылка", 450,
            Color.rgb(121, 68, 24), Color.rgb(78, 42, 14),
            Color.rgb(255, 249, 230), Color.rgb(76, 175, 80), 3
        ),
        MugSkinDef(
            "can", "Банка крафта", 600,
            Color.rgb(30, 136, 229), Color.rgb(21, 101, 192),
            Color.rgb(236, 239, 241), Color.rgb(255, 202, 40), 4
        ),
        MugSkinDef(
            "weizen", "Бокал-вайс", 800,
            Color.rgb(255, 179, 0), Color.rgb(230, 126, 34),
            Color.rgb(255, 253, 248), Color.rgb(255, 245, 157), 5
        ),
        MugSkinDef(
            "bowling_mug", "Кружка-кегля", 1000,
            Color.rgb(250, 250, 250), Color.rgb(189, 189, 189),
            Color.rgb(255, 248, 225), Color.rgb(229, 57, 53), 6
        ),
        MugSkinDef(
            "gold_goblet", "Золотой кубок", 1500,
            Color.rgb(255, 215, 0), Color.rgb(212, 160, 23),
            Color.rgb(255, 253, 231), Color.rgb(229, 57, 53), 7
        ),
        MugSkinDef(
            "neon_mug", "Неоновая кружка", 2000,
            Color.rgb(0, 229, 255), Color.rgb(0, 145, 234),
            Color.rgb(240, 255, 255), Color.rgb(255, 64, 129), 8
        ),
        MugSkinDef(
            "ice_mug", "Ледяная кружка", 0,
            Color.rgb(129, 212, 250), Color.rgb(41, 182, 246),
            Color.rgb(240, 250, 255), Color.rgb(225, 245, 254), 9,
            unlockAchievementId = "ice_heart"
        ),
        MugSkinDef(
            "fire_mug", "Огненная кружка", 0,
            Color.rgb(255, 87, 34), Color.rgb(191, 54, 12),
            Color.rgb(255, 238, 88), Color.rgb(255, 193, 7), 10,
            unlockAchievementId = "beer_baron"
        ),
        MugSkinDef(
            "rainbow_mug", "Радужная кружка", 0,
            Color.rgb(233, 30, 99), Color.rgb(156, 39, 176),
            Color.rgb(255, 255, 255), Color.rgb(0, 230, 118), 11,
            unlockAchievementId = "collector_5"
        )
    )

    // 7 скинов боссов
    val BOSS_SKINS: List<BossSkinDef> = listOf(
        BossSkinDef(
            "fat_bearded", "Толстяк в майке", 0,
            Color.rgb(255, 204, 170), Color.rgb(245, 245, 245),
            Color.rgb(109, 76, 65), Color.rgb(66, 165, 245), 0
        ),
        BossSkinDef(
            "viking", "Викинг с рогами", 400,
            Color.rgb(255, 205, 178), Color.rgb(121, 85, 72),
            Color.rgb(230, 81, 0), Color.rgb(176, 190, 197), 1
        ),
        BossSkinDef(
            "cowboy", "Ковбой", 650,
            Color.rgb(244, 194, 158), Color.rgb(161, 102, 47),
            Color.rgb(93, 64, 55), Color.rgb(229, 57, 53), 2
        ),
        BossSkinDef(
            "biker", "Байкер в кожанке", 900,
            Color.rgb(245, 198, 165), Color.rgb(38, 50, 56),
            Color.rgb(55, 71, 79), Color.rgb(255, 152, 0), 3
        ),
        BossSkinDef(
            "king", "Король с короной", 1300,
            Color.rgb(255, 213, 184), Color.rgb(123, 31, 162),
            Color.rgb(236, 239, 241), Color.rgb(255, 215, 0), 4
        ),
        BossSkinDef(
            "pirate", "Пират", 1700,
            Color.rgb(240, 190, 155), Color.rgb(183, 28, 28),
            Color.rgb(33, 33, 33), Color.rgb(255, 202, 40), 5
        ),
        BossSkinDef(
            "robot", "Робот-бармен", 2200,
            Color.rgb(144, 164, 174), Color.rgb(69, 90, 100),
            Color.rgb(38, 50, 56), Color.rgb(0, 230, 118), 6
        )
    )

    // 7 тематических локаций
    val LOCATIONS: List<LocationDef> = listOf(
        LocationDef(
            "tavern", "Уютный бар", 0,
            Color.rgb(62, 39, 35), Color.rgb(121, 85, 72),
            Color.rgb(161, 110, 62), Color.rgb(141, 92, 48),
            Color.rgb(255, 193, 7), Color.rgb(78, 52, 46), 0
        ),
        LocationDef(
            "ice_cave", "Ледяная пещера", 300,
            Color.rgb(13, 71, 161), Color.rgb(79, 195, 247),
            Color.rgb(179, 229, 252), Color.rgb(129, 212, 250),
            Color.rgb(225, 245, 254), Color.rgb(2, 136, 209), 1
        ),
        LocationDef(
            "beach", "Солнечный пляж", 500,
            Color.rgb(2, 136, 209), Color.rgb(129, 212, 250),
            Color.rgb(255, 224, 130), Color.rgb(255, 213, 79),
            Color.rgb(255, 112, 67), Color.rgb(0, 172, 193), 2
        ),
        LocationDef(
            "desert", "Жаркая пустыня", 750,
            Color.rgb(230, 81, 0), Color.rgb(255, 183, 77),
            Color.rgb(215, 145, 84), Color.rgb(196, 126, 68),
            Color.rgb(141, 110, 99), Color.rgb(239, 108, 0), 3
        ),
        LocationDef(
            "space", "Открытый космос", 1100,
            Color.rgb(10, 12, 34), Color.rgb(49, 27, 146),
            Color.rgb(40, 53, 147), Color.rgb(26, 35, 126),
            Color.rgb(0, 229, 255), Color.rgb(18, 22, 58), 4
        ),
        LocationDef(
            "brewery", "Пивная фабрика", 1400,
            Color.rgb(55, 71, 79), Color.rgb(120, 144, 156),
            Color.rgb(96, 125, 139), Color.rgb(84, 110, 122),
            Color.rgb(255, 138, 101), Color.rgb(69, 90, 100), 5
        ),
        LocationDef(
            "forest", "Баварская деревня", 1700,
            Color.rgb(46, 125, 50), Color.rgb(165, 214, 167),
            Color.rgb(158, 158, 158), Color.rgb(135, 135, 135),
            Color.rgb(141, 110, 99), Color.rgb(67, 160, 71), 6
        )
    )

    // 22 ачивки
    val ACHIEVEMENTS: List<AchievementDef> = listOf(
        AchievementDef("first_sip", "Первый глоток", "Пройди 1-й уровень", 1, 50, "levels_won"),
        AchievementDef("beer_baron", "Пивной барон", "Собери 500 кружек за уровень", 500, 150, "max_mugs_run", "fire_mug"),
        AchievementDef("marathon_10", "Пенный марафон", "Пройди 10 уровней", 10, 200, "levels_won"),
        AchievementDef("legend_25", "Легенда паба", "Пройди 25 уровней", 25, 500, "levels_won"),
        AchievementDef("no_losses", "Без потерь", "Пройди уровень без отрицательных рамок", 1, 100, "clean_gate_wins"),
        AchievementDef("gold_rush", "Золотая жила", "Набери 1000 монет за всё время", 1000, 150, "coins_total"),
        AchievementDef("tycoon", "Пивной магнат", "Набери 5000 монет за всё время", 5000, 400, "coins_total"),
        AchievementDef("collector_5", "Коллекционер", "Открой 5 скинов кружек или боссов", 5, 250, "skins_owned", "rainbow_mug"),
        AchievementDef("wardrobe_master", "Модник бара", "Открой 10 скинов", 10, 500, "skins_owned"),
        AchievementDef("boss_slayer_5", "Укротитель жажды", "Победи 5 разных боссов", 5, 200, "unique_bosses_beaten"),
        AchievementDef("dexterous_20", "Ловкач", "Пройди 20 хороших рамок подряд", 20, 150, "max_good_gate_streak"),
        AchievementDef("flooded_win", "Затопило!", "Получи ÷ и всё равно выиграй уровень", 1, 150, "div_gate_wins"),
        AchievementDef("trap_master", "Мастер ловушек", "Пройди 3 уровня без урона от ловушек", 3, 200, "trapless_wins"),
        AchievementDef("fearless", "Бесстрашный", "Пройди босс-уровень (кратный 5)", 1, 200, "mega_boss_wins"),
        AchievementDef("big_spender", "Транжира", "Потрать 2000 монет в магазине", 2000, 250, "coins_spent"),
        AchievementDef("world_tour", "Весь бар", "Побывай во всех 7 локациях", 7, 300, "unique_locations_visited"),
        AchievementDef("crowd_king", "Царь толпы", "Собери 1500 кружек на одном уровне", 1500, 300, "max_mugs_run"),
        AchievementDef("brawler", "Вышибала", "Победи 100 человечков-противников", 100, 150, "enemies_defeated"),
        AchievementDef("chest_hunter", "Охотник за сундуками", "Подбери 15 сундуков с монетами", 15, 150, "chests_opened"),
        AchievementDef("ice_heart", "Ледяное сердце", "Пройди 3 уровня в ледяной пещере", 3, 150, "ice_cave_wins", "ice_mug"),
        AchievementDef("booster_fan", "Тактик", "Используй 5 бонусов перед стартом", 5, 100, "boosters_used"),
        AchievementDef("upgrader", "Инвестор", "Купи 5 постоянных улучшений", 5, 200, "upgrades_bought")
    )

    fun getMugSkin(id: String): MugSkinDef {
        return MUG_SKINS.find { it.id == id } ?: MUG_SKINS[0]
    }

    fun getBossSkin(id: String): BossSkinDef {
        return BOSS_SKINS.find { it.id == id } ?: BOSS_SKINS[0]
    }

    fun getLocationById(id: String): LocationDef {
        return LOCATIONS.find { it.id == id } ?: LOCATIONS[0]
    }

    fun getLocationForLevel(level: Int): LocationDef {
        val index = ((level - 1).coerceAtLeast(0) / 3) % LOCATIONS.size
        return LOCATIONS[index]
    }

    fun getBossForLevel(level: Int): BossSkinDef {
        val index = (level - 1).coerceAtLeast(0) % BOSS_SKINS.size
        return BOSS_SKINS[index]
    }
}
