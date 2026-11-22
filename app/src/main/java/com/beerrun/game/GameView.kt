package com.beerrun.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    enum class ScreenState {
        MENU,
        LEVEL_SELECT,
        SHOP,
        ACHIEVEMENTS,
        PLAYING,
        PAUSED,
        WIN,
        LOSE
    }

    enum class ShopTab(val title: String) {
        MUGS("Кружки"),
        BOSSES("Боссы"),
        BOOSTERS("Бонусы"),
        BACKGROUNDS("Фоны")
    }

    private class Particle {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 6f
        var color = Color.WHITE
        var life = 0f
        var maxLife = 1f
    }

    private class FlyingMug {
        var active = false
        var startX = 0f
        var startY = 0f
        var targetX = 0f
        var targetY = 0f
        var progress = 0f
    }

    private class FloatingText {
        var active = false
        var text = ""
        var x = 0f
        var y = 0f
        var color = Color.WHITE
        var life = 0f
        var maxLife = 1f
    }

    private class ButtonBox(
        val id: String,
        val rect: RectF = RectF()
    )

    private val prefs = Prefs(context)
    private val sound = Sound(context)
    private val crowd = Crowd()

    private var gameThread: Thread? = null
    @Volatile
    private var isRunning = false
    private var surfaceReady = false

    private var screenW = 1080f
    private var screenH = 1920f
    private var state = ScreenState.MENU
    private var currentShopTab = ShopTab.MUGS

    // Scroll & pagination states for UI screens
    private var scrollOffsetY = 0f
    private var maxScrollY = 0f
    private var levelPage = 0

    // Touch tracking
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingScroll = false
    private var touchMovedDist = 0f

    // Gameplay state (fully reset on every level start!)
    private var levelData: LevelData? = null
    private var playerZ = 0f
    private var slowTimer = 0f
    private var totalTimeSec = 0f

    // Active run boosters & stats
    private var activeDoubleCoins = false
    private var activeIceKeg = false
    private var happyHourTimer = 0f
    private var runCoinsEarned = 0
    private var runGoodGateStreak = 0
    private var runHitBadGate = false
    private var runHitTrap = false
    private var runHitDivGate = false
    private var activeBossFight: Boss? = null
    private var gulpAccumulator = 0f

    // Achievement banner popup
    private var bannerTitle = ""
    private var bannerReward = ""
    private var bannerTimer = 0f

    // Pre-allocated object pools (zero allocation in onDraw)
    private val particles = Array(120) { Particle() }
    private val flyingMugs = Array(60) { FlyingMug() }
    private val floatingTexts = Array(24) { FloatingText() }
    private val uiButtons = ArrayList<ButtonBox>(48)

    // Pre-allocated Paints, Paths, Rects
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val leftTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 225, 235)
        textAlign = Paint.Align.LEFT
    }
    private val tempRect = RectF()
    private val tempRect2 = RectF()
    private val tempPath = Path()
    private val rng = Random(System.currentTimeMillis())

    // Cached sky gradient
    private var cachedSkyTop = 0
    private var cachedSkyBottom = 0
    private var cachedSkyShader: LinearGradient? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        levelPage = (prefs.level - 1) / 20
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat().coerceAtLeast(360f)
        screenH = height.toFloat().coerceAtLeast(640f)
        cachedSkyShader = null
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    fun onHostResume() {
        if (surfaceReady) startLoop()
    }

    fun onHostPause() {
        if (state == ScreenState.PLAYING) {
            state = ScreenState.PAUSED
        }
        stopLoop()
    }

    fun onHostDestroy() {
        stopLoop()
        sound.release()
    }

    /**
     * Обработка системной кнопки "Назад":
     * Возвращает true, если событие перехвачено внутри игры.
     */
    fun handleBackPress(): Boolean {
        return when (state) {
            ScreenState.PLAYING -> {
                sound.playClick()
                state = ScreenState.PAUSED
                true
            }
            ScreenState.PAUSED -> {
                sound.playClick()
                state = ScreenState.PLAYING
                true
            }
            ScreenState.SHOP, ScreenState.LEVEL_SELECT, ScreenState.ACHIEVEMENTS -> {
                sound.playClick()
                scrollOffsetY = 0f
                state = ScreenState.MENU
                true
            }
            ScreenState.WIN, ScreenState.LOSE -> {
                sound.playClick()
                state = ScreenState.MENU
                true
            }
            ScreenState.MENU -> false
        }
    }

    private fun startLoop() {
        if (isRunning) return
        isRunning = true
        gameThread = Thread(this, "BeerRunLoop").apply { start() }
    }

    private fun stopLoop() {
        isRunning = false
        try {
            gameThread?.join(500)
        } catch (_: InterruptedException) {
        }
        gameThread = null
    }

    override fun run() {
        var lastNano = System.nanoTime()
        while (isRunning) {
            val now = System.nanoTime()
            val dt = ((now - lastNano) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastNano = now

            update(dt)

            if (surfaceReady) {
                val canvas = try {
                    holder.lockCanvas()
                } catch (_: Exception) {
                    null
                }
                if (canvas != null) {
                    try {
                        render(canvas)
                    } finally {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            val frameMs = (System.nanoTime() - now) / 1_000_000L
            val sleepMs = (16L - frameMs).coerceAtLeast(1L)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
            }
        }
    }

    /**
     * Полный сброс состояния и запуск выбранного уровня.
     */
    private fun startLevel(targetLevel: Int) {
        val safeLevel = targetLevel.coerceAtLeast(1)
        prefs.level = safeLevel

        // Генерируем уровень строго с нуля по seed номера уровня
        val data = LevelGenerator.generate(safeLevel, prefs)
        levelData = data

        // Сброс всех таймеров и пулов эффектов
        playerZ = 0f
        slowTimer = 0f
        activeBossFight = null
        gulpAccumulator = 0f
        runCoinsEarned = 0
        runGoodGateStreak = 0
        runHitBadGate = false
        runHitTrap = false
        runHitDivGate = false
        activeDoubleCoins = false
        activeIceKeg = false
        happyHourTimer = 0f

        for (p in particles) p.active = false
        for (fm in flyingMugs) fm.active = false
        for (ft in floatingTexts) ft.active = false

        // Стартовое количество кружек: строго Config.START_COUNT + постоянное улучшение + бонус
        var startMugs = Config.START_COUNT + prefs.getUpgradeLevel(Config.UpgradeType.START_MUGS)
        var boostersUsedNow = 0

        if (prefs.isBoosterArmed(Config.BoosterType.START_PACK)) {
            prefs.addBooster(Config.BoosterType.START_PACK, -1)
            startMugs += Config.START_PACK_BONUS
            boostersUsedNow++
        }
        crowd.reset(startMugs)

        if (prefs.isBoosterArmed(Config.BoosterType.MAGNET)) {
            prefs.addBooster(Config.BoosterType.MAGNET, -1)
            crowd.hasMagnet = true
            boostersUsedNow++
        }
        if (prefs.isBoosterArmed(Config.BoosterType.FOAM_SHIELD)) {
            prefs.addBooster(Config.BoosterType.FOAM_SHIELD, -1)
            crowd.hasShield = true
            boostersUsedNow++
        }
        if (prefs.isBoosterArmed(Config.BoosterType.DOUBLE_COINS)) {
            prefs.addBooster(Config.BoosterType.DOUBLE_COINS, -1)
            activeDoubleCoins = true
            boostersUsedNow++
        }
        if (prefs.isBoosterArmed(Config.BoosterType.ICE_KEG)) {
            prefs.addBooster(Config.BoosterType.ICE_KEG, -1)
            activeIceKeg = true
            boostersUsedNow++
        }
        if (prefs.isBoosterArmed(Config.BoosterType.HAPPY_HOUR)) {
            prefs.addBooster(Config.BoosterType.HAPPY_HOUR, -1)
            happyHourTimer = Config.HAPPY_HOUR_DURATION
            boostersUsedNow++
        }

        if (boostersUsedNow > 0) {
            prefs.addStat("boosters_used", boostersUsedNow)
            checkAndShowAchievements()
        }

        prefs.recordLocationVisited(data.location.id)
        checkAndShowAchievements()

        state = ScreenState.PLAYING
    }

    private fun update(dt: Float) {
        totalTimeSec += dt
        if (bannerTimer > 0f) {
            bannerTimer = (bannerTimer - dt).coerceAtLeast(0f)
        }

        updateParticles(dt)
        updateFloatingTexts(dt)

        if (state != ScreenState.PLAYING) return
        val data = levelData ?: return

        if (happyHourTimer > 0f) {
            happyHourTimer = (happyHourTimer - dt).coerceAtLeast(0f)
        }
        if (slowTimer > 0f) {
            slowTimer = (slowTimer - dt).coerceAtLeast(0f)
        }

        crowd.update(dt, totalTimeSec)
        data.miniBoss?.update(dt)
        data.mainBoss.update(dt)

        // Если идёт схватка с боссом (мини-босс или финальный босс)
        val boss = activeBossFight
        if (boss != null) {
            updateBossFight(dt, data, boss)
            return
        }

        // Движение вперёд по дорожке
        val speedMul = if (slowTimer > 0f) 0.52f else 1.0f
        playerZ += Config.BASE_RUN_SPEED * speedMul * dt

        // Отслеживание максимума кружек для ачивок
        prefs.setStatMax("max_mugs_run", crowd.count)

        // 1. Проверка рамок (Gates)
        for (row in data.gateRows) {
            if (!row.triggered && playerZ >= row.z) {
                row.triggered = true
                handleGateRowCollision(row)
                if (crowd.count <= 0) {
                    triggerLose()
                    return
                }
            }
        }

        // 2. Проверка подбираемых предметов (бонусные кружки и сундуки)
        for (pickup in data.pickups) {
            if (pickup.collected) continue
            val dz = pickup.z - playerZ
            if (crowd.hasMagnet && dz in -40f..320f) {
                pickup.x += (crowd.centerX - pickup.x) * (dt * 7f).coerceIn(0f, 1f)
            }
            if (abs(dz) < 38f && abs(pickup.x - crowd.centerX) <= crowd.crowdRadius + 0.24f) {
                pickup.collected = true
                sound.playPickup()
                if (pickup.type == PickupType.BONUS_MUG) {
                    crowd.addCount(pickup.value)
                    spawnFloatingText("+${pickup.value}", crowd.centerX, screenH * 0.68f, Color.rgb(255, 224, 130))
                    spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(255, 213, 79), 10)
                } else {
                    val gained = computeCoinGain(pickup.value)
                    runCoinsEarned += gained
                    prefs.coins += gained
                    prefs.addStat("coins_total", gained)
                    prefs.addStat("chests_opened", 1)
                    spawnFloatingText("+$gained монет", crowd.centerX, screenH * 0.66f, Color.rgb(255, 215, 0))
                    spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(255, 215, 0), 14)
                    checkAndShowAchievements()
                }
            }
        }

        // 3. Проверка препятствий и ловушек
        for (obs in data.obstacles) {
            if (obs.hitTimer > 0f) {
                obs.hitTimer = (obs.hitTimer - dt).coerceAtLeast(0f)
            }
            if (obs.type == ObstacleType.NARROW_BRIDGE) {
                if (playerZ in obs.z..(obs.z + obs.lengthZ) && obs.hitTimer <= 0f) {
                    if (abs(crowd.centerX) > obs.halfWidth) {
                        obs.hitTimer = 0.25f
                        val lost = max(1, (crowd.count * 0.14f).toInt())
                        crowd.addCount(-lost)
                        runHitTrap = true
                        sound.playTrap()
                        spawnFloatingText("-$lost (край моста!)", crowd.centerX, screenH * 0.68f, Color.rgb(255, 112, 67))
                        if (crowd.count <= 0) {
                            triggerLose()
                            return
                        }
                    }
                }
                continue
            }

            if (!obs.triggered && abs(obs.z - playerZ) < 34f) {
                val ox = obs.currentX(totalTimeSec)
                if (abs(ox - crowd.centerX) < (obs.halfWidth + crowd.crowdRadius * 0.65f)) {
                    obs.triggered = true
                    handleObstacleHit(obs)
                    if (crowd.count <= 0) {
                        triggerLose()
                        return
                    }
                }
            }
        }

        // 4. Проверка человечков-противников (EnemyGroup)
        for (enemy in data.enemies) {
            if (enemy.defeated) continue
            if (abs(enemy.z - playerZ) < 42f) {
                val ex = enemy.currentX(totalTimeSec)
                if (abs(ex - crowd.centerX) < (crowd.crowdRadius + 0.28f)) {
                    handleEnemyClash(enemy)
                    if (crowd.count <= 0) {
                        triggerLose()
                        return
                    }
                }
            }
        }

        // 5. Проверка мини-босса посередине уровня
        val mini = data.miniBoss
        if (mini != null && !mini.defeated && playerZ >= mini.z - 55f) {
            playerZ = mini.z - 55f
            activeBossFight = mini
            gulpAccumulator = 0f
            return
        }

        // 6. Проверка финального босса в конце уровня
        if (playerZ >= data.mainBoss.z - 75f) {
            playerZ = data.mainBoss.z - 75f
            activeBossFight = data.mainBoss
            gulpAccumulator = 0f
        }
    }

    private fun handleGateRowCollision(row: GateRow) {
        // Выбираем ближайшую к центру толпы рамку в ряду
        val offsetX = row.getOffsetX(totalTimeSec)
        var chosen: GateItem? = null
        var bestDist = Float.MAX_VALUE
        for (g in row.gates) {
            val gx = g.laneCenter + offsetX
            val dist = abs(gx - crowd.centerX)
            if (dist <= g.halfWidth + 0.14f && dist < bestDist) {
                bestDist = dist
                chosen = g
            }
        }
        val gate = chosen ?: return
        gate.triggered = true

        // Ледяной бочонок превращает первую плохую рамку в +5!
        if (!gate.isPositive() && activeIceKeg) {
            activeIceKeg = false
            gate.op = Config.GateOp.ADD
            gate.value = 5
            sound.playShieldBlock()
            spawnFloatingText("Лёд: +5!", crowd.centerX, screenH * 0.65f, Color.rgb(128, 222, 234))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(128, 222, 234), 16)
        }

        val effVal = gate.getEffectiveValue(happyHourTimer > 0f)
        if (gate.isPositive()) {
            sound.playGateGood()
            runGoodGateStreak++
            prefs.setStatMax("max_good_gate_streak", runGoodGateStreak)
            crowd.applyGate(gate.op, effVal)
            spawnFloatingText(gate.formatLabel(happyHourTimer > 0f), crowd.centerX, screenH * 0.66f, Color.rgb(105, 240, 174))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(105, 240, 174), 14)
        } else {
            sound.playGateBad()
            runGoodGateStreak = 0
            runHitBadGate = true
            if (gate.op == Config.GateOp.DIV) {
                runHitDivGate = true
            }
            crowd.applyGate(gate.op, effVal)
            spawnFloatingText(gate.formatLabel(false), crowd.centerX, screenH * 0.66f, Color.rgb(255, 82, 82))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(255, 82, 82), 14)
        }
        prefs.setStatMax("max_mugs_run", crowd.count)
    }

    private fun handleObstacleHit(obs: ObstacleItem) {
        if (crowd.hasShield) {
            crowd.hasShield = false
            sound.playShieldBlock()
            spawnFloatingText("Щит спас!", crowd.centerX, screenH * 0.66f, Color.rgb(129, 212, 250))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(225, 245, 254), 18)
            return
        }

        runHitTrap = true
        sound.playTrap()
        val px = screenW * 0.5f + crowd.centerX * (screenW * 0.36f)
        val py = screenH * 0.72f

        when (obs.type) {
            ObstacleType.SAW -> {
                val loss = max(3, (crowd.count * 0.25f).toInt())
                crowd.addCount(-loss)
                spawnFloatingText("-$loss", crowd.centerX, screenH * 0.66f, Color.rgb(255, 82, 82))
                spawnBurst(px, py, Color.rgb(255, 138, 101), 16)
            }
            ObstacleType.PUDDLE -> {
                slowTimer = 1.6f
                val loss = max(1, (crowd.count * 0.10f).toInt())
                crowd.addCount(-loss)
                spawnFloatingText("Липко! -$loss", crowd.centerX, screenH * 0.66f, Color.rgb(255, 202, 40))
                spawnBurst(px, py, Color.rgb(255, 179, 0), 12)
            }
            ObstacleType.CRATE -> {
                val loss = max(2, (crowd.count * 0.18f).toInt())
                crowd.addCount(-loss)
                spawnFloatingText("Бах! -$loss", crowd.centerX, screenH * 0.66f, Color.rgb(255, 112, 67))
                spawnBurst(px, py, Color.rgb(161, 136, 127), 14)
            }
            ObstacleType.NARROW_BRIDGE -> Unit
        }
    }

    private fun handleEnemyClash(enemy: EnemyGroup) {
        enemy.defeated = true
        val px = screenW * 0.5f + crowd.centerX * (screenW * 0.36f)
        val py = screenH * 0.70f

        var rivalCount = enemy.count
        if (crowd.hasShield) {
            crowd.hasShield = false
            rivalCount = (rivalCount - 6).coerceAtLeast(0)
            sound.playShieldBlock()
            spawnFloatingText("Щит раскидал врагов!", crowd.centerX, screenH * 0.64f, Color.rgb(129, 212, 250))
        }

        sound.playTrap()
        spawnBurst(px, py, Color.rgb(239, 83, 80), 18)

        if (crowd.count >= rivalCount) {
            crowd.addCount(-rivalCount)
            prefs.addStat("enemies_defeated", enemy.initialCount)
            spawnFloatingText("Победа над толпой! -$rivalCount", crowd.centerX, screenH * 0.68f, Color.rgb(255, 213, 79))
            checkAndShowAchievements()
        } else {
            crowd.setCount(0)
        }
    }

    private fun updateBossFight(dt: Float, data: LevelData, boss: Boss) {
        // Плавно подтягиваем толпу к центру перед боссом
        crowd.centerX += (0f - crowd.centerX) * (dt * 6f).coerceIn(0f, 1f)

        // Обновляем летящие кружки в рот боссу
        for (fm in flyingMugs) {
            if (fm.active) {
                fm.progress += dt * 4.5f
                if (fm.progress >= 1f) {
                    fm.active = false
                }
            }
        }

        val interval = if (boss.isFastBoss) Config.FAST_BOSS_GULP_INTERVAL else Config.NORMAL_BOSS_GULP_INTERVAL
        gulpAccumulator += dt
        while (gulpAccumulator >= interval && !boss.defeated && crowd.count > 0) {
            gulpAccumulator -= interval
            crowd.addCount(-1)
            sound.playGulp()
            spawnFlyingMug()
            val defeatedNow = boss.gulpOne()
            if (defeatedNow) {
                sound.playBurp()
                spawnBurst(screenW * 0.5f, screenH * 0.34f, Color.rgb(255, 215, 0), 32)
                if (boss.isMiniBoss) {
                    val bonus = computeCoinGain(25 + data.levelNumber * 3)
                    runCoinsEarned += bonus
                    prefs.coins += bonus
                    prefs.addStat("coins_total", bonus)
                    spawnFloatingText("Страж повержен! +$bonus монет", 0f, screenH * 0.45f, Color.rgb(255, 215, 0))
                    activeBossFight = null
                    checkAndShowAchievements()
                    return
                } else {
                    triggerWin(data)
                    return
                }
            }
        }

        if (!boss.defeated && crowd.count <= 0) {
            triggerLose()
        }
    }

    private fun triggerWin(data: LevelData) {
        sound.playWin()
        spawnConfetti(screenW * 0.5f, screenH * 0.35f, 70)

        val baseReward = 40 + data.levelNumber * 12 + crowd.count * 2 + (if (data.isMegaBoss) 100 else 0)
        val totalGained = computeCoinGain(baseReward)
        runCoinsEarned += totalGained
        prefs.coins += totalGained
        prefs.addStat("coins_total", totalGained)
        prefs.addStat("levels_won", 1)
        prefs.recordUniqueBossBeaten(data.bossSkin.id)

        if (!runHitBadGate) {
            prefs.addStat("clean_gate_wins", 1)
        }
        if (runHitDivGate) {
            prefs.addStat("div_gate_wins", 1)
        }
        if (!runHitTrap) {
            prefs.addStat("trapless_wins", 1)
        } else {
            // Сбрасываем серию без ловушек если попались
            prefs.setStatMax("trapless_wins", prefs.getStat("trapless_wins"))
        }
        if (data.isMegaBoss) {
            prefs.addStat("mega_boss_wins", 1)
        }
        if (data.location.id == "ice_cave") {
            prefs.addStat("ice_cave_wins", 1)
        }

        val nextLvl = data.levelNumber + 1
        prefs.level = nextLvl
        levelPage = (nextLvl - 1) / 20

        checkAndShowAchievements()
        state = ScreenState.WIN
    }

    private fun triggerLose() {
        sound.playLose()
        activeBossFight = null
        state = ScreenState.LOSE
    }

    private fun computeCoinGain(rawCoins: Int): Int {
        val upgradeBonus = 1.0f + prefs.getUpgradeLevel(Config.UpgradeType.COIN_INCOME) * 0.10f
        val boosterMul = if (activeDoubleCoins) 2.0f else 1.0f
        return (rawCoins * upgradeBonus * boosterMul).toInt().coerceAtLeast(1)
    }

    private fun checkAndShowAchievements() {
        val unlocked = prefs.evaluateAchievements()
        if (unlocked.isNotEmpty()) {
            val first = unlocked[0]
            bannerTitle = "Ачивка: ${first.title}"
            bannerReward = "+${first.rewardCoins} монет" + (if (first.rewardSkinId != null) " + СКИН!" else "")
            bannerTimer = 3.5f
            sound.playAchievement()
        }
    }

    private fun spawnFlyingMug() {
        for (fm in flyingMugs) {
            if (!fm.active) {
                fm.active = true
                fm.startX = screenW * 0.5f + crowd.centerX * (screenW * 0.36f) + (rng.nextFloat() - 0.5f) * 80f
                fm.startY = screenH * 0.72f
                fm.targetX = screenW * 0.5f
                fm.targetY = screenH * 0.34f
                fm.progress = 0f
                return
            }
        }
    }

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int) {
        var spawned = 0
        for (p in particles) {
            if (!p.active) {
                p.active = true
                p.x = x
                p.y = y
                val angle = rng.nextFloat() * 6.283f
                val spd = 110f + rng.nextFloat() * 290f
                p.vx = cos(angle) * spd
                p.vy = sin(angle) * spd - 90f
                p.radius = 5f + rng.nextFloat() * 7f
                p.color = color
                p.life = 0.45f + rng.nextFloat() * 0.35f
                p.maxLife = p.life
                spawned++
                if (spawned >= count) break
            }
        }
    }

    private fun spawnConfetti(x: Float, y: Float, count: Int) {
        val colors = intArrayOf(
            Color.rgb(255, 215, 0),
            Color.rgb(255, 64, 129),
            Color.rgb(0, 229, 255),
            Color.rgb(105, 240, 174),
            Color.rgb(255, 145, 0)
        )
        var spawned = 0
        for (p in particles) {
            if (!p.active) {
                p.active = true
                p.x = x + (rng.nextFloat() - 0.5f) * 160f
                p.y = y
                p.vx = (rng.nextFloat() - 0.5f) * 520f
                p.vy = -180f - rng.nextFloat() * 420f
                p.radius = 7f + rng.nextFloat() * 7f
                p.color = colors[spawned % colors.size]
                p.life = 1.4f + rng.nextFloat() * 0.8f
                p.maxLife = p.life
                spawned++
                if (spawned >= count) break
            }
        }
    }

    private fun spawnFloatingText(text: String, normX: Float, y: Float, color: Int) {
        for (ft in floatingTexts) {
            if (!ft.active) {
                ft.active = true
                ft.text = text
                ft.x = screenW * 0.5f + normX * (screenW * 0.34f)
                ft.y = y
                ft.color = color
                ft.life = 0.95f
                ft.maxLife = 0.95f
                return
            }
        }
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            if (p.active) {
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.vy += 420f * dt
                p.life -= dt
                if (p.life <= 0f) p.active = false
            }
        }
    }

    private fun updateFloatingTexts(dt: Float) {
        for (ft in floatingTexts) {
            if (ft.active) {
                ft.y -= 95f * dt
                ft.life -= dt
                if (ft.life <= 0f) ft.active = false
            }
        }
    }

    // ==================== RENDERING (ZERO ALLOCATIONS) ====================

    private fun render(canvas: Canvas) {
        uiButtons.clear()

        when (state) {
            ScreenState.MENU -> renderMenu(canvas)
            ScreenState.LEVEL_SELECT -> renderLevelSelect(canvas)
            ScreenState.SHOP -> renderShop(canvas)
            ScreenState.ACHIEVEMENTS -> renderAchievements(canvas)
            ScreenState.PLAYING -> renderGameplay(canvas)
            ScreenState.PAUSED -> {
                renderGameplay(canvas)
                renderPauseOverlay(canvas)
            }
            ScreenState.WIN -> {
                renderGameplay(canvas)
                renderWinOverlay(canvas)
            }
            ScreenState.LOSE -> {
                renderGameplay(canvas)
                renderLoseOverlay(canvas)
            }
        }

        renderParticlesAndTexts(canvas)
        renderAchievementBanner(canvas)
    }

    private fun renderGameplay(canvas: Canvas) {
        val data = levelData ?: return
        val loc = data.location

        // 1. Sky background
        if (cachedSkyShader == null || cachedSkyTop != loc.skyTop || cachedSkyBottom != loc.skyBottom) {
            cachedSkyTop = loc.skyTop
            cachedSkyBottom = loc.skyBottom
            cachedSkyShader = LinearGradient(0f, 0f, 0f, screenH * 0.42f, loc.skyTop, loc.skyBottom, Shader.TileMode.CLAMP)
        }
        fillPaint.shader = cachedSkyShader
        canvas.drawRect(0f, 0f, screenW, screenH * 0.42f, fillPaint)
        fillPaint.shader = null

        // Location ambient sky decor (stars in space, sun on beach/desert, etc.)
        drawLocationSkyDecor(canvas, loc)

        // 2. Roadside terrain
        val horizonY = screenH * 0.34f
        fillPaint.color = loc.sideColor
        canvas.drawRect(0f, horizonY, screenW, screenH, fillPaint)

        // 3. Perspective Track
        val bottomHalfW = screenW * 0.43f
        val topHalfW = screenW * 0.13f
        val cx = screenW * 0.5f

        tempPath.reset()
        tempPath.moveTo(cx - topHalfW, horizonY)
        tempPath.lineTo(cx + topHalfW, horizonY)
        tempPath.lineTo(cx + bottomHalfW, screenH)
        tempPath.lineTo(cx - bottomHalfW, screenH)
        tempPath.close()
        fillPaint.color = loc.trackMain
        canvas.drawPath(tempPath, fillPaint)

        // Animated road stripes for speed sensation
        val viewDist = 920f
        val stripeStep = 115f
        val firstStripeIdx = (playerZ / stripeStep).toInt()
        for (idx in firstStripeIdx..(firstStripeIdx + 9)) {
            val z0 = idx * stripeStep - playerZ
            val z1 = z0 + stripeStep * 0.5f
            if (z1 <= 0f || z0 >= viewDist) continue
            val p0 = projectDepth(z0.coerceAtLeast(0f), viewDist)
            val p1 = projectDepth(z1.coerceAtMost(viewDist), viewDist)
            val y0 = horizonY + (screenH - horizonY) * p0
            val y1 = horizonY + (screenH - horizonY) * p1
            val hw0 = topHalfW + (bottomHalfW - topHalfW) * p0
            val hw1 = topHalfW + (bottomHalfW - topHalfW) * p1

            if (idx % 2 == 0) {
                tempPath.reset()
                tempPath.moveTo(cx - hw0, y0)
                tempPath.lineTo(cx + hw0, y0)
                tempPath.lineTo(cx + hw1, y1)
                tempPath.lineTo(cx - hw1, y1)
                tempPath.close()
                fillPaint.color = loc.trackAlt
                canvas.drawPath(tempPath, fillPaint)
            }

            // Roadside thematic props
            drawLocationRoadProp(canvas, loc, idx, cx - hw0 - 28f * p0, y0, p0)
            drawLocationRoadProp(canvas, loc, idx + 1, cx + hw0 + 28f * p0, y0, p0)
        }

        // Track side rails
        strokePaint.color = loc.railColor
        strokePaint.strokeWidth = 8f
        canvas.drawLine(cx - topHalfW, horizonY, cx - bottomHalfW, screenH, strokePaint)
        canvas.drawLine(cx + topHalfW, horizonY, cx + bottomHalfW, screenH, strokePaint)

        // 4. Narrow bridges & puddles on road surface
        for (obs in data.obstacles) {
            if (obs.type == ObstacleType.NARROW_BRIDGE) {
                val relStart = obs.z - playerZ
                val relEnd = obs.z + obs.lengthZ - playerZ
                if (relEnd > -50f && relStart < viewDist) {
                    val p0 = projectDepth(relStart.coerceAtLeast(0f), viewDist)
                    val p1 = projectDepth(relEnd.coerceAtMost(viewDist), viewDist)
                    val y0 = horizonY + (screenH - horizonY) * p0
                    val y1 = horizonY + (screenH - horizonY) * p1
                    val hw0 = topHalfW + (bottomHalfW - topHalfW) * p0
                    val hw1 = topHalfW + (bottomHalfW - topHalfW) * p1

                    // Abyss outside narrow bridge
                    fillPaint.color = Color.argb(185, 15, 20, 35)
                    canvas.drawRect(cx - hw1, y0, cx + hw1, y1, fillPaint)

                    // Safe wooden bridge plank in middle
                    val b0 = hw0 * obs.halfWidth
                    val b1 = hw1 * obs.halfWidth
                    tempPath.reset()
                    tempPath.moveTo(cx - b0, y0)
                    tempPath.lineTo(cx + b0, y0)
                    tempPath.lineTo(cx + b1, y1)
                    tempPath.lineTo(cx - b1, y1)
                    tempPath.close()
                    fillPaint.color = Color.rgb(188, 130, 68)
                    canvas.drawPath(tempPath, fillPaint)
                }
            } else if (obs.type == ObstacleType.PUDDLE && !obs.triggered) {
                val relZ = obs.z - playerZ
                if (relZ in 0f..viewDist) {
                    val p = projectDepth(relZ, viewDist)
                    val sy = horizonY + (screenH - horizonY) * p
                    val hw = topHalfW + (bottomHalfW - topHalfW) * p
                    val sx = cx + obs.currentX(totalTimeSec) * hw
                    val rx = obs.halfWidth * hw
                    val ry = rx * 0.42f
                    fillPaint.color = Color.argb(215, 255, 160, 0)
                    tempRect.set(sx - rx, sy - ry, sx + rx, sy + ry)
                    canvas.drawOval(tempRect, fillPaint)
                }
            }
        }

        // 5. Pickups (bonus mugs & chests)
        val mugSkin = Config.getMugSkin(prefs.selectedSkinId)
        for (pickup in data.pickups) {
            if (pickup.collected) continue
            val relZ = pickup.z - playerZ
            if (relZ in 0f..viewDist) {
                val p = projectDepth(relZ, viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val sx = cx + pickup.x * hw
                val itemSize = (46f * p).coerceAtLeast(14f)

                if (pickup.type == PickupType.BONUS_MUG) {
                    crowd.drawSingleMug(canvas, sx, sy, itemSize, itemSize * 1.35f, mugSkin, totalTimeSec)
                } else {
                    // Coin chest
                    fillPaint.color = Color.rgb(141, 85, 36)
                    tempRect.set(sx - itemSize * 0.65f, sy - itemSize * 0.85f, sx + itemSize * 0.65f, sy)
                    canvas.drawRoundRect(tempRect, 8f, 8f, fillPaint)
                    fillPaint.color = Color.rgb(255, 215, 0)
                    canvas.drawRect(sx - itemSize * 0.65f, sy - itemSize * 0.52f, sx + itemSize * 0.65f, sy - itemSize * 0.36f, fillPaint)
                }
            }
        }

        // 6. Solid Obstacles (Saws & Crates)
        for (obs in data.obstacles) {
            if (obs.triggered || obs.type == ObstacleType.NARROW_BRIDGE || obs.type == ObstacleType.PUDDLE) continue
            val relZ = obs.z - playerZ
            if (relZ in 0f..viewDist) {
                val p = projectDepth(relZ, viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val sx = cx + obs.currentX(totalTimeSec) * hw
                val rx = obs.halfWidth * hw

                if (obs.type == ObstacleType.SAW) {
                    drawSpinningSaw(canvas, sx, sy - rx * 0.35f, rx * 0.85f)
                } else if (obs.type == ObstacleType.CRATE) {
                    fillPaint.color = Color.rgb(161, 102, 47)
                    tempRect.set(sx - rx, sy - rx * 1.15f, sx + rx, sy)
                    canvas.drawRoundRect(tempRect, 10f, 10f, fillPaint)
                    strokePaint.color = Color.rgb(93, 53, 20)
                    strokePaint.strokeWidth = 4f
                    canvas.drawRoundRect(tempRect, 10f, 10f, strokePaint)
                    canvas.drawLine(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom, strokePaint)
                }
            }
        }

        // 7. Enemy Groups (Человечки-противники)
        for (enemy in data.enemies) {
            if (enemy.defeated) continue
            val relZ = enemy.z - playerZ
            if (relZ in 0f..viewDist) {
                val p = projectDepth(relZ, viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val sx = cx + enemy.currentX(totalTimeSec) * hw
                drawEnemyGroup(canvas, sx, sy, p, enemy.count)
            }
        }

        // 8. Gates (Back-to-front)
        val happyActive = happyHourTimer > 0f
        for (i in data.gateRows.size - 1 downTo 0) {
            val row = data.gateRows[i]
            if (row.triggered) continue
            val relZ = row.z - playerZ
            if (relZ in -10f..viewDist) {
                val p = projectDepth(relZ.coerceAtLeast(0f), viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val gateH = (128f * p).coerceAtLeast(24f)
                val offsetX = row.getOffsetX(totalTimeSec)

                for (g in row.gates) {
                    if (g.triggered) continue
                    val gx = cx + (g.laneCenter + offsetX) * hw
                    val gw = g.halfWidth * hw * 0.94f
                    val isGood = g.isPositive()

                    fillPaint.color = when {
                        isGood && happyActive -> Color.argb(195, 171, 71, 188)
                        isGood -> Color.argb(185, 0, 200, 83)
                        activeIceKeg -> Color.argb(185, 41, 182, 246)
                        else -> Color.argb(185, 229, 57, 53)
                    }
                    tempRect.set(gx - gw, sy - gateH, gx + gw, sy)
                    canvas.drawRoundRect(tempRect, 12f * p, 12f * p, fillPaint)

                    strokePaint.color = Color.WHITE
                    strokePaint.strokeWidth = (4f * p).coerceAtLeast(1.5f)
                    canvas.drawRoundRect(tempRect, 12f * p, 12f * p, strokePaint)

                    textPaint.color = Color.WHITE
                    textPaint.textSize = (42f * p).coerceIn(13f, 46f)
                    canvas.drawText(g.formatLabel(happyActive), gx, sy - gateH * 0.34f, textPaint)
                }
            }
        }

        // 9. Mini-Boss & Main Boss
        val mini = data.miniBoss
        if (mini != null && !mini.defeated) {
            val relZ = mini.z - playerZ
            if (relZ in -20f..viewDist) {
                val p = projectDepth(relZ.coerceAtLeast(0f), viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                mini.draw(canvas, cx, sy, screenW * p.coerceAtLeast(0.32f), totalTimeSec)
            }
        }

        val mainBossRelZ = data.mainBoss.z - playerZ
        if (mainBossRelZ in -20f..viewDist * 1.15f) {
            val p = projectDepth(mainBossRelZ.coerceAtLeast(0f), viewDist)
            val sy = horizonY + (screenH - horizonY) * p
            // Finish checkerboard line under boss
            fillPaint.color = Color.WHITE
            val hw = topHalfW + (bottomHalfW - topHalfW) * p
            canvas.drawRect(cx - hw, sy - 10f * p, cx + hw, sy + 6f * p, fillPaint)
            data.mainBoss.draw(canvas, cx, sy, screenW * p.coerceAtLeast(0.38f), totalTimeSec)
        }

        // 10. Player Crowd
        val playerBaseY = screenH * 0.77f
        val playerTrackHalfPx = topHalfW + (bottomHalfW - topHalfW) * projectDepth(110f, viewDist)
        crowd.draw(canvas, cx, playerBaseY, playerTrackHalfPx, mugSkin, totalTimeSec)

        // 11. Flying mugs during boss fight
        for (fm in flyingMugs) {
            if (fm.active) {
                val t = fm.progress
                val fx = fm.startX + (fm.targetX - fm.startX) * t
                val fy = fm.startY + (fm.targetY - fm.startY) * t - sin(t * 3.14159f) * 140f
                crowd.drawSingleMug(canvas, fx, fy, 26f, 36f, mugSkin, totalTimeSec)
            }
        }

        // 12. Gameplay HUD
        renderGameplayHud(canvas, data)
    }

    private fun projectDepth(relZ: Float, viewDist: Float): Float {
        val norm = (1f - (relZ / viewDist)).coerceIn(0f, 1f)
        return norm * norm * 0.78f + norm * 0.22f
    }

    private fun drawSpinningSaw(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (r < 6f) return
        canvas.save()
        canvas.rotate((totalTimeSec * 360f) % 360f, cx, cy)
        fillPaint.color = Color.rgb(207, 216, 220)
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = Color.rgb(229, 57, 53)
        strokePaint.strokeWidth = (r * 0.24f).coerceAtLeast(2f)
        canvas.drawCircle(cx, cy, r * 0.65f, strokePaint)
        fillPaint.color = Color.rgb(55, 71, 79)
        canvas.drawCircle(cx, cy, r * 0.25f, fillPaint)
        canvas.restore()
    }

    private fun drawEnemyGroup(canvas: Canvas, cx: Float, bottomY: Float, scale: Float, count: Int) {
        val unitR = (15f * scale).coerceAtLeast(5f)
        val shown = count.coerceIn(1, 9)
        for (i in 0 until shown) {
            val col = (i % 3) - 1
            val row = i / 3
            val ex = cx + col * unitR * 1.65f
            val ey = bottomY - row * unitR * 1.1f
            // Red jersey body
            fillPaint.color = Color.rgb(211, 47, 47)
            canvas.drawCircle(ex, ey - unitR * 0.9f, unitR * 0.75f, fillPaint)
            // Head
            fillPaint.color = Color.rgb(255, 204, 170)
            canvas.drawCircle(ex, ey - unitR * 1.95f, unitR * 0.55f, fillPaint)
        }
        // Red counter badge
        val badgeY = bottomY - unitR * 3.6f
        fillPaint.color = Color.rgb(183, 28, 28)
        tempRect.set(cx - 32f * scale.coerceAtLeast(0.5f), badgeY - 16f, cx + 32f * scale.coerceAtLeast(0.5f), badgeY + 12f)
        canvas.drawRoundRect(tempRect, 12f, 12f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = (22f * scale).coerceIn(13f, 24f)
        canvas.drawText(count.toString(), cx, badgeY + 5f, textPaint)
    }

    private fun drawLocationSkyDecor(canvas: Canvas, loc: Config.LocationDef) {
        when (loc.decorType) {
            2, 3 -> {
                // Sun
                fillPaint.color = Color.argb(210, 255, 238, 88)
                canvas.drawCircle(screenW * 0.80f, screenH * 0.13f, 68f, fillPaint)
            }
            4 -> {
                // Stars & ringed planet
                fillPaint.color = Color.WHITE
                for (i in 0 until 24) {
                    val sx = ((i * 197) % screenW.toInt()).toFloat()
                    val sy = ((i * 89) % (screenH * 0.30f).toInt()).toFloat()
                    canvas.drawCircle(sx, sy, if (i % 3 == 0) 3.5f else 2f, fillPaint)
                }
                fillPaint.color = Color.rgb(126, 87, 194)
                canvas.drawCircle(screenW * 0.24f, screenH * 0.14f, 48f, fillPaint)
            }
            else -> Unit
        }
    }

    private fun drawLocationRoadProp(
        canvas: Canvas,
        loc: Config.LocationDef,
        seedIdx: Int,
        x: Float,
        y: Float,
        p: Float
    ) {
        val sz = (44f * p).coerceAtLeast(8f)
        when (loc.decorType) {
            1 -> {
                // Ice crystal
                fillPaint.color = Color.rgb(179, 229, 252)
                tempPath.reset()
                tempPath.moveTo(x, y - sz * 1.4f)
                tempPath.lineTo(x + sz * 0.5f, y)
                tempPath.lineTo(x - sz * 0.5f, y)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            3 -> {
                // Cactus
                fillPaint.color = Color.rgb(56, 142, 60)
                canvas.drawRect(x - sz * 0.18f, y - sz * 1.3f, x + sz * 0.18f, y, fillPaint)
            }
            6 -> {
                // Pine tree
                fillPaint.color = Color.rgb(27, 94, 32)
                tempPath.reset()
                tempPath.moveTo(x, y - sz * 1.6f)
                tempPath.lineTo(x + sz * 0.65f, y)
                tempPath.lineTo(x - sz * 0.65f, y)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            else -> {
                // Barrel / Lamp post
                if (seedIdx % 2 == 0) {
                    fillPaint.color = Color.rgb(109, 76, 65)
                    canvas.drawCircle(x, y - sz * 0.4f, sz * 0.42f, fillPaint)
                }
            }
        }
    }

    private fun renderGameplayHud(canvas: Canvas, data: LevelData) {
        // Top pill: Level + Location
        fillPaint.color = Color.argb(175, 20, 22, 32)
        tempRect.set(28f, 36f, screenW * 0.54f, 126f)
        canvas.drawRoundRect(tempRect, 24f, 24f, fillPaint)

        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 30f
        canvas.drawText("Уровень ${data.levelNumber}", 52f, 76f, leftTextPaint)
        subTextPaint.color = Color.rgb(255, 213, 79)
        subTextPaint.textSize = 21f
        canvas.drawText(data.location.name, 52f, 108f, subTextPaint)

        // Progress bar along top
        val prog = (playerZ / max(1f, data.trackLength)).coerceIn(0f, 1f)
        fillPaint.color = Color.argb(140, 255, 255, 255)
        tempRect.set(screenW * 0.25f, 142f, screenW * 0.75f, 156f)
        canvas.drawRoundRect(tempRect, 7f, 7f, fillPaint)
        fillPaint.color = Color.rgb(255, 193, 7)
        tempRect.set(screenW * 0.25f, 142f, screenW * 0.25f + (screenW * 0.50f) * prog, 156f)
        canvas.drawRoundRect(tempRect, 7f, 7f, fillPaint)

        // Coins pill
        fillPaint.color = Color.argb(175, 20, 22, 32)
        tempRect.set(screenW * 0.56f, 36f, screenW - 132f, 126f)
        canvas.drawRoundRect(tempRect, 24f, 24f, fillPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 28f
        canvas.drawText("${prefs.coins}", tempRect.centerX(), 90f, textPaint)

        // Pause Button (top-right corner)
        val pauseRect = RectF(screenW - 116f, 36f, screenW - 28f, 126f)
        fillPaint.color = Color.argb(210, 38, 50, 56)
        canvas.drawRoundRect(pauseRect, 24f, 24f, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(pauseRect, 24f, 24f, strokePaint)

        fillPaint.color = Color.WHITE
        canvas.drawRect(pauseRect.centerX() - 14f, pauseRect.centerY() - 18f, pauseRect.centerX() - 5f, pauseRect.centerY() + 18f, fillPaint)
        canvas.drawRect(pauseRect.centerX() + 5f, pauseRect.centerY() - 18f, pauseRect.centerX() + 14f, pauseRect.centerY() + 18f, fillPaint)
        uiButtons.add(ButtonBox("pause", RectF(pauseRect)))

        // Active booster badges below HUD
        var badgeY = 178f
        if (happyHourTimer > 0f) {
            drawMiniHudTag(canvas, "Счастливый час: ${happyHourTimer.toInt() + 1}с", Color.rgb(171, 71, 188), badgeY)
            badgeY += 42f
        }
        if (activeIceKeg) {
            drawMiniHudTag(canvas, "Ледяной бочонок готов", Color.rgb(41, 182, 246), badgeY)
            badgeY += 42f
        }
        if (activeDoubleCoins) {
            drawMiniHudTag(canvas, "Золотой множитель x2", Color.rgb(255, 179, 0), badgeY)
        }
    }

    private fun drawMiniHudTag(canvas: Canvas, label: String, color: Int, y: Float) {
        fillPaint.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
        tempRect.set(28f, y, 360f, y + 34f)
        canvas.drawRoundRect(tempRect, 17f, 17f, fillPaint)
        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 20f
        canvas.drawText(label, 44f, y + 24f, leftTextPaint)
    }

    // ==================== MENU & SCREENS ====================

    private fun renderMenu(canvas: Canvas) {
        // Warm tavern gradient background
        fillPaint.color = Color.rgb(34, 22, 18)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        fillPaint.color = Color.argb(40, 255, 193, 7)
        canvas.drawCircle(screenW * 0.5f, screenH * 0.28f, screenW * 0.52f, fillPaint)

        // Top Coins & Sound Toggle
        fillPaint.color = Color.argb(190, 55, 38, 30)
        tempRect.set(32f, 42f, 320f, 114f)
        canvas.drawRoundRect(tempRect, 36f, 36f, fillPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 30f
        canvas.drawText("Монеты: ${prefs.coins}", tempRect.centerX(), 88f, textPaint)

        val muteRect = RectF(screenW - 124f, 42f, screenW - 32f, 114f)
        fillPaint.color = Color.argb(190, 55, 38, 30)
        canvas.drawRoundRect(muteRect, 24f, 24f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
        canvas.drawText(if (sound.muted) "OFF" else "ЗВУК", muteRect.centerX(), 86f, textPaint)
        uiButtons.add(ButtonBox("toggle_mute", RectF(muteRect)))

        // Game Logo
        textPaint.color = Color.rgb(255, 193, 7)
        textPaint.textSize = 78f
        canvas.drawText("BEER RUN", screenW * 0.5f, screenH * 0.16f, textPaint)
        textPaint.color = Color.rgb(255, 248, 225)
        textPaint.textSize = 28f
        canvas.drawText("Пенный забег • Уровень ${prefs.level}", screenW * 0.5f, screenH * 0.205f, textPaint)

        // Animated preview of selected mug skin
        val selectedSkin = Config.getMugSkin(prefs.selectedSkinId)
        val previewBob = sin(totalTimeSec * 4f) * 10f
        crowd.drawSingleMug(
            canvas,
            screenW * 0.5f,
            screenH * 0.37f + previewBob,
            135f,
            185f,
            selectedSkin,
            totalTimeSec
        )

        // Pre-run Booster quick-toggle strip
        textPaint.color = Color.rgb(220, 210, 195)
        textPaint.textSize = 23f
        canvas.drawText("Бонусы на забег (нажми чтобы взять):", screenW * 0.5f, screenH * 0.425f, textPaint)

        val boosters = Config.BoosterType.values()
        val cellW = (screenW - 84f) / 3f
        val cellH = 84f
        for (i in boosters.indices) {
            val b = boosters[i]
            val col = i % 3
            val row = i / 3
            val bx = 32f + col * (cellW + 10f)
            val by = screenH * 0.44f + row * (cellH + 10f)
            val rect = RectF(bx, by, bx + cellW, by + cellH)
            val armed = prefs.isBoosterArmed(b)
            val cnt = prefs.getBoosterCount(b)

            fillPaint.color = when {
                armed -> Color.rgb(46, 125, 50)
                cnt > 0 -> Color.rgb(66, 48, 38)
                else -> Color.rgb(44, 34, 28)
            }
            canvas.drawRoundRect(rect, 18f, 18f, fillPaint)
            strokePaint.color = if (armed) Color.rgb(105, 240, 174) else b.color
            strokePaint.strokeWidth = if (armed) 4f else 2f
            canvas.drawRoundRect(rect, 18f, 18f, strokePaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 20f
            canvas.drawText(b.title, rect.centerX(), by + 34f, textPaint)
            textPaint.color = if (cnt > 0) Color.rgb(255, 215, 0) else Color.GRAY
            textPaint.textSize = 20f
            val statusTxt = if (armed) "ВЗЯТО (x$cnt)" else "Запас: x$cnt"
            canvas.drawText(statusTxt, rect.centerX(), by + 64f, textPaint)

            uiButtons.add(ButtonBox("arm_booster:${b.id}", rect))
        }

        // 4 Main Menu Buttons
        val btnStartY = screenH * 0.59f
        val btnW = screenW - 120f
        val btnH = 92f
        val gap = 20f

        drawMenuButton(canvas, "play_current", "ИГРАТЬ (Ур. ${prefs.level})", 60f, btnStartY, btnW, btnH, Color.rgb(255, 160, 0), Color.rgb(40, 20, 5))
        drawMenuButton(canvas, "open_levels", "ВЫБОР УРОВНЯ", 60f, btnStartY + (btnH + gap), btnW, btnH * 0.88f, Color.rgb(30, 136, 229), Color.WHITE)
        drawMenuButton(canvas, "open_shop", "МАГАЗИН", 60f, btnStartY + (btnH + gap) * 1.92f, btnW, btnH * 0.88f, Color.rgb(67, 160, 71), Color.WHITE)
        drawMenuButton(canvas, "open_achievements", "АЧИВКИ", 60f, btnStartY + (btnH + gap) * 2.84f, btnW, btnH * 0.88f, Color.rgb(142, 36, 170), Color.WHITE)
    }

    private fun drawMenuButton(
        canvas: Canvas,
        id: String,
        label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        bgColor: Int,
        txtColor: Int
    ) {
        val rect = RectF(x, y, x + w, y + h)
        fillPaint.color = bgColor
        canvas.drawRoundRect(rect, 26f, 26f, fillPaint)
        strokePaint.color = Color.argb(120, 255, 255, 255)
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(rect, 26f, 26f, strokePaint)

        textPaint.color = txtColor
        textPaint.textSize = 32f
        canvas.drawText(label, rect.centerX(), rect.centerY() + 11f, textPaint)
        uiButtons.add(ButtonBox(id, rect))
    }

    private fun renderLevelSelect(canvas: Canvas) {
        fillPaint.color = Color.rgb(24, 28, 42)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        renderTopHeader(canvas, "ВЫБОР УРОВНЯ")

        val maxUnlocked = prefs.maxUnlockedLevel
        val startLvl = levelPage * 20 + 1
        val cols = 4
        val pad = 28f
        val gap = 18f
        val cellSize = (screenW - pad * 2f - gap * (cols - 1)) / cols
        val gridTop = 185f

        for (i in 0 until 20) {
            val lvl = startLvl + i
            val col = i % cols
            val row = i / cols
            val x = pad + col * (cellSize + gap)
            val y = gridTop + row * (cellSize + gap)
            val rect = RectF(x, y, x + cellSize, y + cellSize)
            val unlocked = lvl <= maxUnlocked
            val isBossLvl = (lvl % 5 == 0)

            fillPaint.color = when {
                !unlocked -> Color.rgb(45, 50, 65)
                isBossLvl -> Color.rgb(194, 24, 91)
                lvl == prefs.level -> Color.rgb(255, 160, 0)
                else -> Color.rgb(30, 136, 229)
            }
            canvas.drawRoundRect(rect, 22f, 22f, fillPaint)

            if (unlocked) {
                textPaint.color = Color.WHITE
                textPaint.textSize = 34f
                canvas.drawText("$lvl", rect.centerX(), rect.centerY() + 8f, textPaint)
                if (isBossLvl) {
                    textPaint.textSize = 16f
                    canvas.drawText("БОСС", rect.centerX(), rect.bottom - 14f, textPaint)
                }
                uiButtons.add(ButtonBox("select_level:$lvl", rect))
            } else {
                // Padlock icon
                strokePaint.color = Color.rgb(144, 164, 174)
                strokePaint.strokeWidth = 4f
                tempRect.set(rect.centerX() - 14f, rect.centerY() - 26f, rect.centerX() + 14f, rect.centerY())
                canvas.drawArc(tempRect, 180f, 180f, false, strokePaint)
                fillPaint.color = Color.rgb(144, 164, 174)
                tempRect2.set(rect.centerX() - 18f, rect.centerY() - 10f, rect.centerX() + 18f, rect.centerY() + 18f)
                canvas.drawRoundRect(tempRect2, 6f, 6f, fillPaint)
                textPaint.color = Color.rgb(176, 190, 197)
                textPaint.textSize = 18f
                canvas.drawText("$lvl", rect.centerX(), rect.bottom - 12f, textPaint)
            }
        }

        // Page navigation
        val navY = gridTop + 5 * (cellSize + gap) + 25f
        if (levelPage > 0) {
            drawMenuButton(canvas, "lvl_prev_page", "< Назад", 36f, navY, 240f, 76f, Color.rgb(69, 90, 100), Color.WHITE)
        }
        drawMenuButton(canvas, "lvl_next_page", "Вперёд >", screenW - 276f, navY, 240f, 76f, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderShop(canvas: Canvas) {
        fillPaint.color = Color.rgb(26, 22, 34)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        // 4 Tabs
        val tabs = ShopTab.values()
        val tabW = (screenW - 48f) / tabs.size
        val tabTop = 148f
        val tabH = 64f
        for (i in tabs.indices) {
            val t = tabs[i]
            val tx = 24f + i * tabW
            val rect = RectF(tx + 3f, tabTop, tx + tabW - 3f, tabTop + tabH)
            val active = (t == currentShopTab)
            fillPaint.color = if (active) Color.rgb(255, 160, 0) else Color.rgb(52, 46, 66)
            canvas.drawRoundRect(rect, 16f, 16f, fillPaint)
            textPaint.color = if (active) Color.rgb(30, 15, 5) else Color.WHITE
            textPaint.textSize = 22f
            canvas.drawText(t.title, rect.centerX(), rect.centerY() + 8f, textPaint)
            uiButtons.add(ButtonBox("shop_tab:${t.name}", rect))
        }

        val listTop = 228f
        canvas.save()
        canvas.clipRect(0f, listTop, screenW, screenH)

        var curY = listTop + 12f - scrollOffsetY
        val cardH = 136f
        val cardGap = 16f

        when (currentShopTab) {
            ShopTab.MUGS -> {
                for (skin in Config.MUG_SKINS) {
                    val unlocked = prefs.isMugSkinUnlocked(skin.id)
                    val selected = prefs.selectedSkinId == skin.id
                    val btnLabel = when {
                        selected -> "Выбрано"
                        unlocked -> "Выбрать"
                        skin.unlockAchievementId != null -> "Ачивка"
                        else -> "${skin.price} м."
                    }
                    val btnColor = when {
                        selected -> Color.rgb(46, 125, 50)
                        unlocked -> Color.rgb(30, 136, 229)
                        skin.unlockAchievementId != null -> Color.rgb(106, 27, 154)
                        prefs.coins >= skin.price -> Color.rgb(255, 160, 0)
                        else -> Color.rgb(90, 90, 100)
                    }
                    val sub = if (!unlocked && skin.unlockAchievementId != null) {
                        val achName = Config.ACHIEVEMENTS.find { it.id == skin.unlockAchievementId }?.title ?: "Достижение"
                        "Откроется за ачивку «$achName»"
                    } else {
                        "Скин пивной кружки"
                    }
                    drawShopCard(canvas, curY, cardH, skin.name, sub, btnLabel, btnColor, "buy_mug:${skin.id}") { cx, cy ->
                        crowd.drawSingleMug(canvas, cx, cy + 36f, 54f, 74f, skin, totalTimeSec)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BOSSES -> {
                // Auto option
                val autoSel = prefs.selectedBossId == "auto"
                drawShopCard(
                    canvas, curY, cardH,
                    "По уровню (Авто)", "Боссы меняются каждый уровень",
                    if (autoSel) "Выбрано" else "Выбрать",
                    if (autoSel) Color.rgb(46, 125, 50) else Color.rgb(30, 136, 229),
                    "buy_boss:auto"
                ) { cx, cy ->
                    fillPaint.color = Color.rgb(255, 193, 7)
                    canvas.drawCircle(cx, cy, 30f, fillPaint)
                    textPaint.color = Color.BLACK
                    textPaint.textSize = 24f
                    canvas.drawText("AUTO", cx, cy + 8f, textPaint)
                }
                curY += cardH + cardGap

                for (boss in Config.BOSS_SKINS) {
                    val unlocked = prefs.isBossSkinUnlocked(boss.id)
                    val selected = prefs.selectedBossId == boss.id
                    val btnLabel = when {
                        selected -> "Выбрано"
                        unlocked -> "Выбрать"
                        else -> "${boss.price} м."
                    }
                    val btnColor = when {
                        selected -> Color.rgb(46, 125, 50)
                        unlocked -> Color.rgb(30, 136, 229)
                        prefs.coins >= boss.price -> Color.rgb(255, 160, 0)
                        else -> Color.rgb(90, 90, 100)
                    }
                    drawShopCard(canvas, curY, cardH, boss.name, "Облик финального босса", btnLabel, btnColor, "buy_boss:${boss.id}") { cx, cy ->
                        fillPaint.color = boss.outfitColor
                        canvas.drawCircle(cx, cy + 12f, 28f, fillPaint)
                        fillPaint.color = boss.skinColor
                        canvas.drawCircle(cx, cy - 16f, 20f, fillPaint)
                        fillPaint.color = boss.accentColor
                        canvas.drawCircle(cx, cy - 32f, 10f, fillPaint)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BOOSTERS -> {
                // Постоянные улучшения
                for (upg in Config.UpgradeType.values()) {
                    val lvl = prefs.getUpgradeLevel(upg)
                    val maxed = lvl >= upg.maxLevel
                    val price = prefs.getUpgradePrice(upg)
                    val btnLabel = if (maxed) "МАКС" else "$price м."
                    val btnColor = when {
                        maxed -> Color.rgb(46, 125, 50)
                        prefs.coins >= price -> Color.rgb(255, 160, 0)
                        else -> Color.rgb(90, 90, 100)
                    }
                    drawShopCard(
                        canvas, curY, cardH,
                        "${upg.title} (Ур. $lvl/${upg.maxLevel})",
                        upg.desc,
                        btnLabel, btnColor,
                        "buy_upgrade:${upg.id}"
                    ) { cx, cy ->
                        fillPaint.color = Color.rgb(255, 215, 0)
                        canvas.drawCircle(cx, cy, 30f, fillPaint)
                        textPaint.color = Color.rgb(40, 20, 5)
                        textPaint.textSize = 26f
                        canvas.drawText("UP", cx, cy + 9f, textPaint)
                    }
                    curY += cardH + cardGap
                }

                // Расходники
                for (b in Config.BoosterType.values()) {
                    val cnt = prefs.getBoosterCount(b)
                    val btnColor = if (prefs.coins >= b.price) Color.rgb(255, 160, 0) else Color.rgb(90, 90, 100)
                    drawShopCard(
                        canvas, curY, cardH,
                        "${b.title} (Запас: x$cnt)",
                        b.desc,
                        "${b.price} м.", btnColor,
                        "buy_booster:${b.id}"
                    ) { cx, cy ->
                        fillPaint.color = b.color
                        canvas.drawCircle(cx, cy, 30f, fillPaint)
                        textPaint.color = Color.WHITE
                        textPaint.textSize = 24f
                        canvas.drawText("+1", cx, cy + 8f, textPaint)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BACKGROUNDS -> {
                val autoSel = prefs.selectedLocationId == "auto"
                drawShopCard(
                    canvas, curY, cardH,
                    "Смена каждые 3 уровня", "Автоматическое путешествие по локациям",
                    if (autoSel) "Выбрано" else "Выбрать",
                    if (autoSel) Color.rgb(46, 125, 50) else Color.rgb(30, 136, 229),
                    "buy_loc:auto"
                ) { cx, cy ->
                    fillPaint.color = Color.rgb(0, 188, 212)
                    canvas.drawCircle(cx, cy, 30f, fillPaint)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = 22f
                    canvas.drawText("MIX", cx, cy + 8f, textPaint)
                }
                curY += cardH + cardGap

                for (loc in Config.LOCATIONS) {
                    val unlocked = prefs.isLocationUnlocked(loc.id)
                    val selected = prefs.selectedLocationId == loc.id
                    val btnLabel = when {
                        selected -> "Выбрано"
                        unlocked -> "Выбрать"
                        else -> "${loc.price} м."
                    }
                    val btnColor = when {
                        selected -> Color.rgb(46, 125, 50)
                        unlocked -> Color.rgb(30, 136, 229)
                        prefs.coins >= loc.price -> Color.rgb(255, 160, 0)
                        else -> Color.rgb(90, 90, 100)
                    }
                    drawShopCard(canvas, curY, cardH, loc.name, "Тематический фон и трасса", btnLabel, btnColor, "buy_loc:${loc.id}") { cx, cy ->
                        fillPaint.color = loc.skyTop
                        canvas.drawCircle(cx, cy, 32f, fillPaint)
                        fillPaint.color = loc.trackMain
                        canvas.drawCircle(cx, cy + 12f, 18f, fillPaint)
                    }
                    curY += cardH + cardGap
                }
            }
        }

        val totalContentH = (curY + scrollOffsetY) - listTop
        maxScrollY = (totalContentH - (screenH - listTop) + 40f).coerceAtLeast(0f)

        canvas.restore()
        renderTopHeader(canvas, "МАГАЗИН")
    }

    private inline fun drawShopCard(
        canvas: Canvas,
        y: Float,
        h: Float,
        title: String,
        subtitle: String,
        btnLabel: String,
        btnColor: Int,
        actionId: String,
        drawPreview: (Float, Float) -> Unit
    ) {
        if (y + h < 180f || y > screenH) return
        val cardRect = RectF(24f, y, screenW - 24f, y + h)
        fillPaint.color = Color.rgb(42, 37, 56)
        canvas.drawRoundRect(cardRect, 22f, 22f, fillPaint)

        // Icon preview circle
        fillPaint.color = Color.rgb(30, 26, 40)
        canvas.drawCircle(86f, y + h * 0.5f, 44f, fillPaint)
        drawPreview(86f, y + h * 0.5f)

        // Title & subtitle
        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 25f
        canvas.drawText(title, 148f, y + 52f, leftTextPaint)

        subTextPaint.color = Color.rgb(190, 195, 210)
        subTextPaint.textSize = 20f
        canvas.drawText(subtitle, 148f, y + 88f, subTextPaint)

        // Action button
        val btnRect = RectF(screenW - 215f, y + 32f, screenW - 42f, y + h - 32f)
        fillPaint.color = btnColor
        canvas.drawRoundRect(btnRect, 18f, 18f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 23f
        canvas.drawText(btnLabel, btnRect.centerX(), btnRect.centerY() + 8f, textPaint)

        if (y + h > 228f) {
            uiButtons.add(ButtonBox(actionId, btnRect))
        }
    }

    private fun renderAchievements(canvas: Canvas) {
        fillPaint.color = Color.rgb(22, 26, 38)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val listTop = 148f
        canvas.save()
        canvas.clipRect(0f, listTop, screenW, screenH)

        var curY = listTop + 12f - scrollOffsetY
        val cardH = 132f
        val gap = 14f

        for (ach in Config.ACHIEVEMENTS) {
            val unlocked = prefs.isAchievementUnlocked(ach.id)
            val progress = prefs.getStat(ach.statKey).coerceAtMost(ach.target)
            if (curY + cardH >= listTop && curY <= screenH) {
                val rect = RectF(24f, curY, screenW - 24f, curY + cardH)
                fillPaint.color = if (unlocked) Color.rgb(35, 68, 52) else Color.rgb(38, 44, 62)
                canvas.drawRoundRect(rect, 20f, 20f, fillPaint)

                // Status badge
                fillPaint.color = if (unlocked) Color.rgb(105, 240, 174) else Color.rgb(84, 110, 122)
                canvas.drawCircle(74f, curY + cardH * 0.5f, 28f, fillPaint)
                textPaint.color = Color.rgb(20, 30, 25)
                textPaint.textSize = 26f
                canvas.drawText(if (unlocked) "✓" else "★", 74f, curY + cardH * 0.5f + 9f, textPaint)

                leftTextPaint.color = Color.WHITE
                leftTextPaint.textSize = 25f
                canvas.drawText(ach.title, 120f, curY + 42f, leftTextPaint)

                subTextPaint.color = Color.rgb(200, 208, 220)
                subTextPaint.textSize = 20f
                canvas.drawText(ach.desc, 120f, curY + 72f, subTextPaint)

                // Progress bar
                val barLeft = 120f
                val barRight = screenW - 190f
                val barTop = curY + 88f
                val barBottom = curY + 110f
                fillPaint.color = Color.argb(140, 0, 0, 0)
                tempRect.set(barLeft, barTop, barRight, barBottom)
                canvas.drawRoundRect(tempRect, 11f, 11f, fillPaint)

                val ratio = (progress.toFloat() / max(1, ach.target)).coerceIn(0f, 1f)
                fillPaint.color = if (unlocked) Color.rgb(105, 240, 174) else Color.rgb(255, 193, 7)
                tempRect.set(barLeft, barTop, barLeft + (barRight - barLeft) * ratio, barBottom)
                canvas.drawRoundRect(tempRect, 11f, 11f, fillPaint)

                textPaint.color = Color.rgb(255, 215, 0)
                textPaint.textSize = 21f
                canvas.drawText("+${ach.rewardCoins} м.", screenW - 105f, curY + 56f, textPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 19f
                canvas.drawText("$progress/${ach.target}", screenW - 105f, curY + 104f, textPaint)
            }
            curY += cardH + gap
        }

        val totalH = (curY + scrollOffsetY) - listTop
        maxScrollY = (totalH - (screenH - listTop) + 40f).coerceAtLeast(0f)

        canvas.restore()
        renderTopHeader(canvas, "АЧИВКИ (${Config.ACHIEVEMENTS.count { prefs.isAchievementUnlocked(it.id) }}/${Config.ACHIEVEMENTS.size})")
    }

    private fun renderTopHeader(canvas: Canvas, title: String) {
        fillPaint.color = Color.rgb(18, 20, 30)
        canvas.drawRect(0f, 0f, screenW, 136f, fillPaint)

        val backRect = RectF(24f, 36f, 168f, 112f)
        fillPaint.color = Color.rgb(55, 65, 85)
        canvas.drawRoundRect(backRect, 20f, 20f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
        canvas.drawText("< Меню", backRect.centerX(), backRect.centerY() + 8f, textPaint)
        uiButtons.add(ButtonBox("back_menu", backRect))

        textPaint.color = Color.WHITE
        textPaint.textSize = 30f
        canvas.drawText(title, screenW * 0.54f, 84f, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 24f
        canvas.drawText("${prefs.coins} м.", screenW - 76f, 84f, textPaint)
    }

    private fun renderPauseOverlay(canvas: Canvas) {
        fillPaint.color = Color.argb(185, 10, 12, 20)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 120f
        val panelH = 490f
        val px = 60f
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(36, 40, 56)
        canvas.drawRoundRect(tempRect, 32f, 32f, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 46f
        canvas.drawText("ПАУЗА", screenW * 0.5f, py + 82f, textPaint)

        val btnW = panelW - 80f
        val btnH = 84f
        drawMenuButton(canvas, "pause_resume", "Продолжить", px + 40f, py + 130f, btnW, btnH, Color.rgb(67, 160, 71), Color.WHITE)
        drawMenuButton(canvas, "pause_restart", "Заново", px + 40f, py + 236f, btnW, btnH, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "pause_menu", "В главное меню", px + 40f, py + 342f, btnW, btnH, Color.rgb(229, 57, 53), Color.WHITE)
    }

    private fun renderWinOverlay(canvas: Canvas) {
        fillPaint.color = Color.argb(175, 12, 24, 18)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 100f
        val panelH = 520f
        val px = 50f
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(30, 52, 42)
        canvas.drawRoundRect(tempRect, 32f, 32f, fillPaint)

        textPaint.color = Color.rgb(105, 240, 174)
        textPaint.textSize = 50f
        canvas.drawText("ПОБЕДА!", screenW * 0.5f, py + 86f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 28f
        canvas.drawText("Босс повержен! Осталось кружек: ${crowd.count}", screenW * 0.5f, py + 146f, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 34f
        canvas.drawText("Награда: +$runCoinsEarned монет", screenW * 0.5f, py + 202f, textPaint)

        val btnW = panelW - 80f
        drawMenuButton(canvas, "win_next", "Следующий уровень", px + 40f, py + 265f, btnW, 88f, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "win_menu", "В главное меню", px + 40f, py + 375f, btnW, 82f, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderLoseOverlay(canvas: Canvas) {
        fillPaint.color = Color.argb(185, 28, 12, 14)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 100f
        val panelH = 480f
        val px = 50f
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(54, 28, 34)
        canvas.drawRoundRect(tempRect, 32f, 32f, fillPaint)

        textPaint.color = Color.rgb(255, 82, 82)
        textPaint.textSize = 48f
        canvas.drawText("НЕ ХВАТИЛО ПЕНЫ!", screenW * 0.5f, py + 86f, textPaint)

        textPaint.color = Color.rgb(230, 220, 220)
        textPaint.textSize = 25f
        canvas.drawText("Собирай больше кружек через x и +", screenW * 0.5f, py + 148f, textPaint)

        val btnW = panelW - 80f
        drawMenuButton(canvas, "lose_retry", "Попробовать заново", px + 40f, py + 225f, btnW, 88f, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "lose_menu", "В главное меню", px + 40f, py + 335f, btnW, 82f, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderParticlesAndTexts(canvas: Canvas) {
        for (p in particles) {
            if (p.active) {
                val alpha = ((p.life / p.maxLife) * 255).toInt().coerceIn(0, 255)
                fillPaint.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(p.x, p.y, p.radius, fillPaint)
            }
        }
        for (ft in floatingTexts) {
            if (ft.active) {
                val alpha = ((ft.life / ft.maxLife) * 255).toInt().coerceIn(0, 255)
                textPaint.color = Color.argb(alpha, Color.red(ft.color), Color.green(ft.color), Color.blue(ft.color))
                textPaint.textSize = 30f
                canvas.drawText(ft.text, ft.x, ft.y, textPaint)
            }
        }
    }

    private fun renderAchievementBanner(canvas: Canvas) {
        if (bannerTimer <= 0f) return
        val slide = min(1f, bannerTimer * 3f) * min(1f, (3.5f - bannerTimer) * 5f + 0.2f)
        val top = -110f + 135f * slide
        tempRect.set(40f, top, screenW - 40f, top + 96f)
        fillPaint.color = Color.rgb(46, 125, 50)
        canvas.drawRoundRect(tempRect, 24f, 24f, fillPaint)
        strokePaint.color = Color.rgb(255, 215, 0)
        strokePaint.strokeWidth = 4f
        canvas.drawRoundRect(tempRect, 24f, 24f, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 26f
        canvas.drawText(bannerTitle, screenW * 0.5f, top + 40f, textPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 22f
        canvas.drawText(bannerReward, screenW * 0.5f, top + 74f, textPaint)
    }

    // ==================== INPUT HANDLING ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                lastTouchX = x
                lastTouchY = y
                touchMovedDist = 0f
                isDraggingScroll = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                touchMovedDist += abs(dx) + abs(dy)
                lastTouchX = x
                lastTouchY = y

                if (state == ScreenState.PLAYING && activeBossFight == null) {
                    val sensitivity = 2.35f / screenW
                    crowd.centerX += dx * sensitivity
                } else if (state == ScreenState.SHOP || state == ScreenState.ACHIEVEMENTS) {
                    if (touchMovedDist > 18f) {
                        isDraggingScroll = true
                        scrollOffsetY = (scrollOffsetY - dy).coerceIn(0f, maxScrollY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDraggingScroll && touchMovedDist < 28f) {
                    handleTap(x, y)
                }
                isDraggingScroll = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        for (i in uiButtons.size - 1 downTo 0) {
            val btn = uiButtons[i]
            if (btn.rect.contains(x, y)) {
                sound.playClick()
                executeButtonAction(btn.id)
                return
            }
        }
    }

    private fun executeButtonAction(id: String) {
        when {
            id == "toggle_mute" -> {
                sound.muted = !sound.muted
            }
            id.startsWith("arm_booster:") -> {
                val bId = id.substringAfter(":")
                val b = Config.BoosterType.values().find { it.id == bId } ?: return
                if (prefs.getBoosterCount(b) > 0) {
                    prefs.setBoosterArmed(b, !prefs.isBoosterArmed(b))
                } else {
                    currentShopTab = ShopTab.BOOSTERS
                    scrollOffsetY = 0f
                    state = ScreenState.SHOP
                }
            }
            id == "play_current" -> {
                startLevel(prefs.level)
            }
            id == "open_levels" -> {
                levelPage = (prefs.level - 1) / 20
                state = ScreenState.LEVEL_SELECT
            }
            id == "open_shop" -> {
                scrollOffsetY = 0f
                state = ScreenState.SHOP
            }
            id == "open_achievements" -> {
                scrollOffsetY = 0f
                state = ScreenState.ACHIEVEMENTS
            }
            id == "back_menu" || id == "pause_menu" || id == "win_menu" || id == "lose_menu" -> {
                scrollOffsetY = 0f
                state = ScreenState.MENU
            }
            id == "pause" -> {
                state = ScreenState.PAUSED
            }
            id == "pause_resume" -> {
                state = ScreenState.PLAYING
            }
            id == "pause_restart" || id == "lose_retry" -> {
                val cur = levelData?.levelNumber ?: prefs.level
                startLevel(cur)
            }
            id == "win_next" -> {
                startLevel(prefs.level)
            }
            id.startsWith("select_level:") -> {
                val lvl = id.substringAfter(":").toIntOrNull() ?: 1
                if (lvl <= prefs.maxUnlockedLevel) {
                    startLevel(lvl)
                }
            }
            id == "lvl_prev_page" -> {
                if (levelPage > 0) levelPage--
            }
            id == "lvl_next_page" -> {
                if ((levelPage + 1) * 20 < prefs.maxUnlockedLevel + 20) levelPage++
            }
            id.startsWith("shop_tab:") -> {
                val tabName = id.substringAfter(":")
                currentShopTab = ShopTab.valueOf(tabName)
                scrollOffsetY = 0f
            }
            id.startsWith("buy_mug:") -> {
                val skinId = id.substringAfter(":")
                val skin = Config.getMugSkin(skinId)
                if (prefs.isMugSkinUnlocked(skin.id)) {
                    prefs.selectedSkinId = skin.id
                } else if (skin.unlockAchievementId == null && prefs.coins >= skin.price) {
                    prefs.coins -= skin.price
                    prefs.addStat("coins_spent", skin.price)
                    prefs.unlockMugSkin(skin.id)
                    prefs.selectedSkinId = skin.id
                    checkAndShowAchievements()
                }
            }
            id.startsWith("buy_boss:") -> {
                val bossId = id.substringAfter(":")
                if (bossId == "auto") {
                    prefs.selectedBossId = "auto"
                } else {
                    val boss = Config.getBossSkin(bossId)
                    if (prefs.isBossSkinUnlocked(boss.id)) {
                        prefs.selectedBossId = boss.id
                    } else if (prefs.coins >= boss.price) {
                        prefs.coins -= boss.price
                        prefs.addStat("coins_spent", boss.price)
                        prefs.unlockBossSkin(boss.id)
                        prefs.selectedBossId = boss.id
                        checkAndShowAchievements()
                    }
                }
            }
            id.startsWith("buy_upgrade:") -> {
                val upgId = id.substringAfter(":")
                val upg = Config.UpgradeType.values().find { it.id == upgId } ?: return
                if (prefs.getUpgradeLevel(upg) < upg.maxLevel) {
                    val price = prefs.getUpgradePrice(upg)
                    if (prefs.coins >= price) {
                        prefs.coins -= price
                        prefs.addStat("coins_spent", price)
                        prefs.addStat("upgrades_bought", 1)
                        prefs.incrementUpgrade(upg)
                        checkAndShowAchievements()
                    }
                }
            }
            id.startsWith("buy_booster:") -> {
                val bId = id.substringAfter(":")
                val b = Config.BoosterType.values().find { it.id == bId } ?: return
                if (prefs.coins >= b.price) {
                    prefs.coins -= b.price
                    prefs.addStat("coins_spent", b.price)
                    prefs.addBooster(b, 1)
                    prefs.setBoosterArmed(b, true)
                    checkAndShowAchievements()
                }
            }
            id.startsWith("buy_loc:") -> {
                val locId = id.substringAfter(":")
                if (locId == "auto") {
                    prefs.selectedLocationId = "auto"
                } else {
                    val loc = Config.getLocationById(locId)
                    if (prefs.isLocationUnlocked(loc.id)) {
                        prefs.selectedLocationId = loc.id
                    } else if (prefs.coins >= loc.price) {
                        prefs.coins -= loc.price
                        prefs.addStat("coins_spent", loc.price)
                        prefs.unlockLocation(loc.id)
                        prefs.selectedLocationId = loc.id
                        checkAndShowAchievements()
                    }
                }
            }
        }
    }
}
