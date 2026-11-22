package com.beerrun.game

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("beer_run_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val GIST_FILENAME = "BeerRun_SaveData.json"
        private const val CODE_PREFIX = "BEER1:"
    }

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

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) {
            prefs.edit().putString("github_token", value.trim()).apply()
        }

    var githubGistId: String
        get() = prefs.getString("github_gist_id", "") ?: ""
        set(value) {
            prefs.edit().putString("github_gist_id", value.trim()).apply()
        }

    var githubUserLogin: String
        get() = prefs.getString("github_user_login", "") ?: ""
        set(value) {
            prefs.edit().putString("github_user_login", value.trim()).apply()
        }

    var lastCloudSync: String
        get() = prefs.getString("github_last_sync", "") ?: ""
        set(value) {
            prefs.edit().putString("github_last_sync", value).apply()
        }

    fun clearGitHubLink() {
        prefs.edit()
            .remove("github_token")
            .remove("github_gist_id")
            .remove("github_user_login")
            .remove("github_last_sync")
            .apply()
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

    // ==================== EXPORT / IMPORT & CLOUD SYNC ====================

    fun exportToJsonString(): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("ts", System.currentTimeMillis())
        root.put("level", level)
        root.put("maxUnlockedLevel", maxUnlockedLevel)
        root.put("coins", coins)
        root.put("selectedSkinId", selectedSkinId)
        root.put("selectedBossId", selectedBossId)
        root.put("selectedLocationId", selectedLocationId)

        root.put("unlockedSkins", JSONArray(prefs.getStringSet("unlockedSkins", emptySet())?.toList() ?: emptyList<String>()))
        root.put("unlockedBosses", JSONArray(prefs.getStringSet("unlockedBosses", emptySet())?.toList() ?: emptyList<String>()))
        root.put("unlockedLocations", JSONArray(prefs.getStringSet("unlockedLocations", emptySet())?.toList() ?: emptyList<String>()))
        root.put("beatenBosses", JSONArray(prefs.getStringSet("beaten_boss_ids", emptySet())?.toList() ?: emptyList<String>()))
        root.put("visitedLocations", JSONArray(prefs.getStringSet("visited_location_ids", emptySet())?.toList() ?: emptyList<String>()))

        val boostersObj = JSONObject()
        for (b in Config.BoosterType.values()) {
            boostersObj.put(b.id, getBoosterCount(b))
        }
        root.put("boosters", boostersObj)

        val upgradesObj = JSONObject()
        for (u in Config.UpgradeType.values()) {
            upgradesObj.put(u.id, getUpgradeLevel(u))
        }
        root.put("upgrades", upgradesObj)

        val statsObj = JSONObject()
        val achArr = JSONArray()
        for ((k, v) in prefs.all) {
            if (k.startsWith("stat_") && v is Int) {
                statsObj.put(k.removePrefix("stat_"), v)
            } else if (k.startsWith("ach_unlocked_") && v == true) {
                achArr.put(k.removePrefix("ach_unlocked_"))
            }
        }
        root.put("stats", statsObj)
        root.put("achievements", achArr)

        return root.toString()
    }

    fun importFromJsonString(rawJson: String): Result<String> {
        return try {
            val root = JSONObject(rawJson.trim())
            if (!root.has("level") || !root.has("coins")) {
                return Result.failure(IllegalArgumentException("Неверный формат сохранения"))
            }

            val editor = prefs.edit()
            val newLevel = root.optInt("level", 1).coerceAtLeast(1)
            val newMaxLevel = root.optInt("maxUnlockedLevel", newLevel).coerceAtLeast(newLevel)
            val newCoins = root.optInt("coins", 0).coerceAtLeast(0)

            editor.putInt("level", newLevel)
            editor.putInt("maxUnlockedLevel", newMaxLevel)
            editor.putInt("coins", newCoins)
            editor.putString("selectedSkinId", root.optString("selectedSkinId", "classic_pint"))
            editor.putString("selectedBossId", root.optString("selectedBossId", "auto"))
            editor.putString("selectedLocationId", root.optString("selectedLocationId", "auto"))

            editor.putStringSet("unlockedSkins", jsonArrayToSet(root.optJSONArray("unlockedSkins"), "classic_pint"))
            editor.putStringSet("unlockedBosses", jsonArrayToSet(root.optJSONArray("unlockedBosses"), "fat_bearded"))
            editor.putStringSet("unlockedLocations", jsonArrayToSet(root.optJSONArray("unlockedLocations"), "tavern"))
            editor.putStringSet("beaten_boss_ids", jsonArrayToSet(root.optJSONArray("beatenBosses"), null))
            editor.putStringSet("visited_location_ids", jsonArrayToSet(root.optJSONArray("visitedLocations"), null))

            val boostersObj = root.optJSONObject("boosters")
            for (b in Config.BoosterType.values()) {
                val cnt = boostersObj?.optInt(b.id, 0)?.coerceAtLeast(0) ?: 0
                editor.putInt("booster_count_${b.id}", cnt)
                if (cnt == 0) {
                    editor.putBoolean("booster_armed_${b.id}", false)
                }
            }

            val upgradesObj = root.optJSONObject("upgrades")
            for (u in Config.UpgradeType.values()) {
                val lvl = upgradesObj?.optInt(u.id, 0)?.coerceIn(0, u.maxLevel) ?: 0
                editor.putInt("upgrade_${u.id}", lvl)
            }

            // Очищаем старые счетчики и ачивки перед записью импортированных
            for (k in prefs.all.keys) {
                if (k.startsWith("stat_") || k.startsWith("ach_unlocked_")) {
                    editor.remove(k)
                }
            }

            val statsObj = root.optJSONObject("stats")
            if (statsObj != null) {
                val keys = statsObj.keys()
                while (keys.hasNext()) {
                    val statKey = keys.next()
                    editor.putInt("stat_$statKey", statsObj.optInt(statKey, 0))
                }
            }

            val achArr = root.optJSONArray("achievements")
            if (achArr != null) {
                for (i in 0 until achArr.length()) {
                    val achId = achArr.optString(i, "")
                    if (achId.isNotEmpty()) {
                        editor.putBoolean("ach_unlocked_$achId", true)
                    }
                }
            }

            editor.apply()
            updateOwnedSkinsStat()
            Result.success("Ур. $newLevel • $newCoins монет")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun exportTransferCode(): String {
        val jsonBytes = exportToJsonString().toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(jsonBytes) }
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return CODE_PREFIX + b64
    }

    fun importTransferCode(rawInput: String): Result<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Пустой код"))
        }
        if (trimmed.startsWith("{")) {
            return importFromJsonString(trimmed)
        }
        return try {
            val payload = if (trimmed.startsWith(CODE_PREFIX)) {
                trimmed.removePrefix(CODE_PREFIX).trim()
            } else {
                trimmed
            }
            val compressed = Base64.decode(payload, Base64.DEFAULT)
            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            importFromJsonString(jsonStr)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Неверный или повреждённый код"))
        }
    }

    fun saveToGitHubGist(onComplete: (Boolean, String) -> Unit) {
        val token = githubToken.trim()
        if (token.isEmpty()) {
            onComplete(false, "Сначала введи GitHub Token")
            return
        }

        Thread {
            try {
                var currentGistId = githubGistId
                if (currentGistId.isEmpty()) {
                    val found = findExistingBeerRunGist(token)
                    if (found != null) {
                        currentGistId = found.first
                        githubGistId = found.first
                        if (found.second.isNotEmpty()) githubUserLogin = found.second
                    }
                }

                val fileEntry = JSONObject().apply {
                    put("content", exportToJsonString())
                }
                val filesObj = JSONObject().apply {
                    put(GIST_FILENAME, fileEntry)
                }
                val bodyObj = JSONObject().apply {
                    put("description", "Beer Run Android Save Data")
                    put("public", false)
                    put("files", filesObj)
                }
                val payload = bodyObj.toString()

                var response: Pair<Int, String>? = null
                if (currentGistId.isNotEmpty()) {
                    val patchRes = executeGitHubRequest("PATCH", "https://api.github.com/gists/$currentGistId", token, payload)
                    if (patchRes.first == 404) {
                        githubGistId = ""
                    } else {
                        response = patchRes
                    }
                }

                if (response == null) {
                    response = executeGitHubRequest("POST", "https://api.github.com/gists", token, payload)
                }

                val (code, respBody) = response
                if (code in 200..299) {
                    val respJson = JSONObject(respBody)
                    val savedId = respJson.optString("id", "")
                    if (savedId.isNotEmpty()) githubGistId = savedId
                    val login = respJson.optJSONObject("owner")?.optString("login", "") ?: ""
                    if (login.isNotEmpty()) githubUserLogin = login

                    val stamp = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date())
                    lastCloudSync = stamp
                    val userTag = if (login.isNotEmpty()) " (@$login)" else ""
                    onComplete(true, "Сохранено в GitHub$userTag")
                } else if (code == 401 || code == 403) {
                    onComplete(false, "Ошибка доступа ($code): проверь право 'gist' у токена")
                } else {
                    onComplete(false, "Ошибка GitHub: HTTP $code")
                }
            } catch (e: Exception) {
                onComplete(false, "Нет сети или ошибка: ${e.message ?: "сбой"}")
            }
        }.start()
    }

    fun loadFromGitHubGist(onComplete: (Boolean, String) -> Unit) {
        val token = githubToken.trim()
        if (token.isEmpty()) {
            onComplete(false, "Сначала введи GitHub Token")
            return
        }

        Thread {
            try {
                var targetGistId = githubGistId
                if (targetGistId.isEmpty()) {
                    val found = findExistingBeerRunGist(token)
                    if (found != null) {
                        targetGistId = found.first
                        githubGistId = found.first
                        if (found.second.isNotEmpty()) githubUserLogin = found.second
                    }
                }

                if (targetGistId.isEmpty()) {
                    onComplete(false, "Сохранение на этом аккаунте GitHub не найдено")
                    return@Thread
                }

                var getRes = executeGitHubRequest("GET", "https://api.github.com/gists/$targetGistId", token, null)
                if (getRes.first == 404) {
                    val found = findExistingBeerRunGist(token)
                    if (found != null) {
                        targetGistId = found.first
                        githubGistId = found.first
                        getRes = executeGitHubRequest("GET", "https://api.github.com/gists/$targetGistId", token, null)
                    }
                }

                val (code, respBody) = getRes
                if (code !in 200..299) {
                    onComplete(false, "Не удалось загрузить (HTTP $code)")
                    return@Thread
                }

                val gistJson = JSONObject(respBody)
                val login = gistJson.optJSONObject("owner")?.optString("login", "") ?: ""
                if (login.isNotEmpty()) githubUserLogin = login

                val files = gistJson.optJSONObject("files")
                val saveFile = files?.optJSONObject(GIST_FILENAME)
                val content = saveFile?.optString("content", "") ?: ""
                if (content.isEmpty()) {
                    onComplete(false, "Файл сохранения в Gist пуст")
                    return@Thread
                }

                val importRes = importFromJsonString(content)
                if (importRes.isSuccess) {
                    val stamp = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date())
                    lastCloudSync = stamp
                    onComplete(true, "Загружено: ${importRes.getOrNull()}")
                } else {
                    onComplete(false, "Файл сохранения повреждён")
                }
            } catch (e: Exception) {
                onComplete(false, "Ошибка сети: ${e.message ?: "сбой"}")
            }
        }.start()
    }

    private fun findExistingBeerRunGist(token: String): Pair<String, String>? {
        val (code, body) = executeGitHubRequest("GET", "https://api.github.com/gists?per_page=50", token, null)
        if (code == 401 || code == 403) {
            throw IllegalStateException("Неверный токен ($code)")
        }
        if (code !in 200..299) return null

        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val files = item.optJSONObject("files") ?: continue
            if (files.has(GIST_FILENAME)) {
                val id = item.optString("id", "")
                val login = item.optJSONObject("owner")?.optString("login", "") ?: ""
                if (id.isNotEmpty()) {
                    return id to login
                }
            }
        }
        return null
    }

    private fun executeGitHubRequest(
        method: String,
        urlStr: String,
        token: String,
        jsonBody: String?
    ): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") {
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "BeerRun-Android")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }

        if (jsonBody != null) {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun jsonArrayToSet(arr: JSONArray?, mandatoryItem: String?): Set<String> {
        val set = HashSet<String>()
        if (mandatoryItem != null) set.add(mandatoryItem)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) set.add(s)
            }
        }
        return set
    }
}
