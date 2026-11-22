package com.beerrun.game

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("beer_run_prefs", Context.MODE_PRIVATE)

    var level: Int
        get() = prefs.getInt("level", 1).coerceAtLeast(1)
        set(value) {
            val safe = value.coerceAtLeast(1)
            prefs.edit().putInt("level", safe).apply()
            if (safe > maxUnlockedLevel) {
                maxUnlockedLevel = safe
            }
        }

    var maxUnlockedLevel: Int
        get() = prefs.getInt("maxUnlockedLevel", level).coerceAtLeast(level)
        set(value) {
            prefs.edit().putInt("maxUnlockedLevel", value.coerceAtLeast(1)).apply()
        }

    var coins: Int
        get() = prefs.getInt("coins", 0).coerceAtLeast(0)
        set(value) {
            prefs.edit().putInt("coins", value.coerceAtLeast(0)).apply()
        }

    var selectedSkinId: String
        get() = prefs.getString("selectedSkinId", "classic_pint") ?: "classic_pint"
        set(value) {
            prefs.edit().putString("selectedSkinId", value).apply()
        }

    var selectedBossId: String
        get() = prefs.getString("selectedBossId", "auto") ?: "auto"
        set(value) {
            prefs.edit().putString("selectedBossId", value).apply()
        }

    var selectedLocationId: String
        get() = prefs.getString("selectedLocationId", "auto") ?: "auto"
        set(value) {
            prefs.edit().putString("selectedLocationId", value).apply()
        }

    fun isMugSkinUnlocked(id: String): Boolean {
        if (id == "classic_pint") return true
        val set = prefs.getStringSet("unlockedSkins", emptySet()) ?: emptySet()
        return set.contains(id)
    }

    fun unlockMugSkin(id: String) {
        val current = HashSet(prefs.getStringSet("unlockedSkins", emptySet()) ?: emptySet())
        current.add("classic_pint")
        current.add(id)
        prefs.edit().putStringSet("unlockedSkins", current).apply()
        updateOwnedSkinsStat()
    }

    fun isBossSkinUnlocked(id: String): Boolean {
        if (id == "fat_bearded" || id == "auto") return true
        val set = prefs.getStringSet("unlockedBosses", emptySet()) ?: emptySet()
        return set.contains(id)
    }

    fun unlockBossSkin(id: String) {
        val current = HashSet(prefs.getStringSet("unlockedBosses", emptySet()) ?: emptySet())
        current.add("fat_bearded")
        current.add(id)
        prefs.edit().putStringSet("unlockedBosses", current).apply()
        updateOwnedSkinsStat()
    }

    fun isLocationUnlocked(id: String): Boolean {
        if (id == "tavern" || id == "auto") return true
        val set = prefs.getStringSet("unlockedLocations", emptySet()) ?: emptySet()
        return set.contains(id)
    }

    fun unlockLocation(id: String) {
        val current = HashSet(prefs.getStringSet("unlockedLocations", emptySet()) ?: emptySet())
        current.add("tavern")
        current.add(id)
        prefs.edit().putStringSet("unlockedLocations", current).apply()
    }

    private fun updateOwnedSkinsStat() {
        val mugs = HashSet(prefs.getStringSet("unlockedSkins", emptySet()) ?: emptySet())
        mugs.add("classic_pint")
        val bosses = HashSet(prefs.getStringSet("unlockedBosses", emptySet()) ?: emptySet())
        bosses.add("fat_bearded")
        setStatMax("skins_owned", mugs.size + bosses.size)
    }

    fun getBoosterCount(type: Config.BoosterType): Int {
        return prefs.getInt("booster_count_${type.id}", 0).coerceAtLeast(0)
    }

    fun addBooster(type: Config.BoosterType, delta: Int) {
        val next = (getBoosterCount(type) + delta).coerceAtLeast(0)
        prefs.edit().putInt("booster_count_${type.id}", next).apply()
        if (next == 0) {
            setBoosterArmed(type, false)
        }
    }

    fun isBoosterArmed(type: Config.BoosterType): Boolean {
        return getBoosterCount(type) > 0 && prefs.getBoolean("booster_armed_${type.id}", false)
    }

    fun setBoosterArmed(type: Config.BoosterType, armed: Boolean) {
        val valid = armed && getBoosterCount(type) > 0
        prefs.edit().putBoolean("booster_armed_${type.id}", valid).apply()
    }

    fun getUpgradeLevel(type: Config.UpgradeType): Int {
        return prefs.getInt("upgrade_${type.id}", 0).coerceIn(0, type.maxLevel)
    }

    fun getUpgradePrice(type: Config.UpgradeType): Int {
        val lvl = getUpgradeLevel(type)
        return type.basePrice * (lvl + 1)
    }

    fun incrementUpgrade(type: Config.UpgradeType) {
        val next = (getUpgradeLevel(type) + 1).coerceAtMost(type.maxLevel)
        prefs.edit().putInt("upgrade_${type.id}", next).apply()
    }

    fun recordUniqueBossBeaten(bossId: String) {
        val current = HashSet(prefs.getStringSet("beaten_boss_ids", emptySet()) ?: emptySet())
        current.add(bossId)
        prefs.edit().putStringSet("beaten_boss_ids", current).apply()
        setStatMax("unique_bosses_beaten", current.size)
    }

    fun recordLocationVisited(locationId: String) {
        val current = HashSet(prefs.getStringSet("visited_location_ids", emptySet()) ?: emptySet())
        current.add(locationId)
        prefs.edit().putStringSet("visited_location_ids", current).apply()
        setStatMax("unique_locations_visited", current.size)
    }

    fun getStat(key: String): Int {
        if (key == "skins_owned") {
            val mugs = HashSet(prefs.getStringSet("unlockedSkins", emptySet()) ?: emptySet())
            mugs.add("classic_pint")
            val bosses = HashSet(prefs.getStringSet("unlockedBosses", emptySet()) ?: emptySet())
            bosses.add("fat_bearded")
            return maxOf(prefs.getInt("stat_$key", 0), mugs.size + bosses.size)
        }
        return prefs.getInt("stat_$key", 0)
    }

    fun addStat(key: String, delta: Int) {
        if (delta <= 0) return
        val next = getStat(key) + delta
        prefs.edit().putInt("stat_$key", next).apply()
    }

    fun setStatMax(key: String, value: Int) {
        val cur = getStat(key)
        if (value > cur) {
            prefs.edit().putInt("stat_$key", value).apply()
        }
    }

    fun isAchievementUnlocked(id: String): Boolean {
        return prefs.getBoolean("ach_unlocked_$id", false)
    }

    fun evaluateAchievements(): List<Config.AchievementDef> {
        updateOwnedSkinsStat()
        val newlyUnlocked = ArrayList<Config.AchievementDef>()
        for (ach in Config.ACHIEVEMENTS) {
            if (!isAchievementUnlocked(ach.id)) {
                val progress = getStat(ach.statKey)
                if (progress >= ach.target) {
                    prefs.edit().putBoolean("ach_unlocked_${ach.id}", true).apply()
                    coins += ach.rewardCoins
                    addStat("coins_total", ach.rewardCoins)
                    if (ach.rewardSkinId != null) {
                        unlockMugSkin(ach.rewardSkinId)
                    }
                    newlyUnlocked.add(ach)
                }
            }
        }
        return newlyUnlocked
    }
}
