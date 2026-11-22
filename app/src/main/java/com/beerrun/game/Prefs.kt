package com.beerrun.game

import android.content.Context
import android.content.SharedPreferences

/**
 * Сохранение уровня, монет и купленных скинов в SharedPreferences.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("beer_run_prefs", Context.MODE_PRIVATE)

    var level: Int
        get() = prefs.getInt("level", 1).coerceAtLeast(1)
        set(value) = prefs.edit().putInt("level", value.coerceAtLeast(1)).apply()

    var coins: Int
        get() = prefs.getInt("coins", 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt("coins", value.coerceAtLeast(0)).apply()

    var selectedSkinId: Int
        get() = prefs.getInt("selected_skin", Config.Skin.GLASS_MUG.id)
        set(value) = prefs.edit().putInt("selected_skin", value).apply()

    fun isSkinUnlocked(skin: Config.Skin): Boolean {
        if (skin.price == 0) return true
        return prefs.getBoolean("skin_unlocked_${skin.id}", false)
    }

    fun unlockSkin(skin: Config.Skin) {
        prefs.edit().putBoolean("skin_unlocked_${skin.id}", true).apply()
    }
}
