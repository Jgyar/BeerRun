package com.beerrun.game

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.InputType
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val VIEW_DIST = 1250f
        private const val HORIZON_RATIO = 0.34f
        private const val PLAYER_BASE_Y_RATIO = 0.77f
        // Точное смещение Z, соответствующее экранной позиции толпы (77% высоты экрана)
        private const val CROWD_VISUAL_REL_Z = 270f
    }

    enum class ScreenState {
        MENU,
        LEVEL_SELECT,
        SHOP,
        ACHIEVEMENTS,
        ACCOUNT,
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

    private class ButtonBox {
        var id: String = ""
        val rect: RectF = RectF()
    }

    private val prefs = Prefs(context)
    private val sound = Sound(context)
    private val crowd = Crowd()

    private var gameThread: Thread? = null
    @Volatile
    private var isRunning = false
    private var surfaceReady = false

    private var screenW = 1080f
    private var screenH = 1920f
    private var uiScale = 1.45f

    @Volatile
    private var state = ScreenState.MENU
    private var currentShopTab = ShopTab.MUGS

    // Account screen feedback state
    @Volatile
    private var accountStatusText = ""
    @Volatile
    private var accountStatusColor = Color.rgb(105, 240, 174)
    @Volatile
    private var isCloudBusy = false

    // Scroll & pagination states for UI screens
    private var scrollOffsetY = 0f
    private var maxScrollY = 0f
    private var levelPage = 0

    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingScroll = false
    private var touchMovedDist = 0f

    // Gameplay state (fully reset on every level start!)
    private var levelData: LevelData? = null
    private var playerZ = 0f
    private var runElapsedSec = 0f
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

    // Double-buffered thread-safe button pool (instant response, zero race conditions)
    private val buttonPool = Array(80) { ButtonBox() }
    private var buttonPoolIdx = 0
    private val buttonsA = ArrayList<ButtonBox>(48)
    private val buttonsB = ArrayList<ButtonBox>(48)
    @Volatile
    private var readableButtons: List<ButtonBox> = buttonsA
    private var stagingButtons: ArrayList<ButtonBox> = buttonsA

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
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 15, 15, 20)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
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
        uiScale = min(screenW / 720f, screenH / 1280f).coerceIn(0.85f, 1.85f)
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
            ScreenState.SHOP, ScreenState.LEVEL_SELECT, ScreenState.ACHIEVEMENTS, ScreenState.ACCOUNT -> {
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

    private fun startLevel(targetLevel: Int) {
        val safeLevel = targetLevel.coerceAtLeast(1)
        prefs.level = safeLevel

        val data = LevelGenerator.generate(safeLevel, prefs)
        levelData = data

        playerZ = 0f
        runElapsedSec = 0f
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

        runElapsedSec += dt
        if (happyHourTimer > 0f) {
            happyHourTimer = (happyHourTimer - dt).coerceAtLeast(0f)
        }
        if (slowTimer > 0f) {
            slowTimer = (slowTimer - dt).coerceAtLeast(0f)
        }

        crowd.update(dt, totalTimeSec)
        data.miniBoss?.update(dt)
        data.mainBoss.update(dt)

        val boss = activeBossFight
        if (boss != null) {
            updateBossFight(dt, data, boss)
            return
        }

        // Плавный разгон в первые 1.2 секунды уровня + спокойная базовая скорость
        val startRamp = (0.65f + 0.35f * (runElapsedSec / 1.2f)).coerceIn(0.65f, 1.0f)
        val slowMul = if (slowTimer > 0f) 0.55f else 1.0f
        playerZ += Config.BASE_RUN_SPEED * startRamp * slowMul * dt

        // Координата Z самой толпы кружек на трассе (совпадает с визуальной позицией на экране)
        val crowdZ = playerZ + CROWD_VISUAL_REL_Z

        prefs.setStatMax("max_mugs_run", crowd.count)

        // 1. Проверка рамок (Gates) — срабатывает мгновенно в момент касания кружками
        for (row in data.gateRows) {
            if (!row.triggered && crowdZ >= row.z - 10f) {
                row.triggered = true
                handleGateRowCollision(row)
                if (crowd.count <= 0) {
                    triggerLose()
                    return
                }
            }
        }

        // 2. Проверка подбираемых предметов
        for (pickup in data.pickups) {
            if (pickup.collected) continue
            val dz = pickup.z - crowdZ
            if (crowd.hasMagnet && dz in -40f..420f) {
                pickup.x += (crowd.centerX - pickup.x) * (dt * 8f).coerceIn(0f, 1f)
            }
            if (abs(dz) < 42f && abs(pickup.x - crowd.centerX) <= crowd.crowdRadius + 0.26f) {
                pickup.collected = true
                sound.playPickup()
                if (pickup.type == PickupType.BONUS_MUG) {
                    crowd.addCount(pickup.value)
                    spawnFloatingText("+${pickup.value} кружек", crowd.centerX, screenH * 0.62f, Color.rgb(255, 224, 130))
                    spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(255, 213, 79), 10)
                } else {
                    val gained = computeCoinGain(pickup.value)
                    runCoinsEarned += gained
                    prefs.coins += gained
                    prefs.addStat("coins_total", gained)
                    prefs.addStat("chests_opened", 1)
                    spawnFloatingText("+$gained монет", crowd.centerX, screenH * 0.60f, Color.rgb(255, 215, 0))
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
                if (crowdZ in obs.z..(obs.z + obs.lengthZ) && obs.hitTimer <= 0f) {
                    if (abs(crowd.centerX) > obs.halfWidth) {
                        obs.hitTimer = 0.32f
                        val lost = max(1, (crowd.count * 0.14f).toInt())
                        crowd.addCount(-lost)
                        runHitTrap = true
                        sound.playTrap()
                        spawnFloatingText("-$lost (край моста!)", crowd.centerX, screenH * 0.62f, Color.rgb(255, 112, 67))
                        if (crowd.count <= 0) {
                            triggerLose()
                            return
                        }
                    }
                }
                continue
            }

            if (!obs.triggered && abs(obs.z - crowdZ) < 36f) {
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

        // 4. Проверка человечков-противников
        for (enemy in data.enemies) {
            if (enemy.defeated) continue
            if (abs(enemy.z - crowdZ) < 44f) {
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

        // 5. Проверка мини-босса
        val mini = data.miniBoss
        if (mini != null && !mini.defeated && crowdZ >= mini.z - 60f) {
            playerZ = mini.z - 60f - CROWD_VISUAL_REL_Z
            activeBossFight = mini
            gulpAccumulator = 0f
            return
        }

        // 6. Проверка финального босса
        if (crowdZ >= data.mainBoss.z - 75f) {
            playerZ = data.mainBoss.z - 75f - CROWD_VISUAL_REL_Z
            activeBossFight = data.mainBoss
            gulpAccumulator = 0f
        }
    }

    private fun handleGateRowCollision(row: GateRow) {
        val offsetX = row.getOffsetX(totalTimeSec)
        var chosen: GateItem? = null
        var bestDist = Float.MAX_VALUE
        val reachBonus = max(0.16f, crowd.crowdRadius * 0.65f)
        for (g in row.gates) {
            val gx = g.laneCenter + offsetX
            val dist = abs(gx - crowd.centerX)
            if (dist <= g.halfWidth + reachBonus && dist < bestDist) {
                bestDist = dist
                chosen = g
            }
        }
        val gate = chosen ?: return
        gate.triggered = true

        if (!gate.isPositive() && activeIceKeg) {
            activeIceKeg = false
            gate.op = Config.GateOp.ADD
            gate.value = 5
            sound.playShieldBlock()
            spawnFloatingText("Лёд: +5!", crowd.centerX, screenH * 0.62f, Color.rgb(128, 222, 234))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(128, 222, 234), 16)
        }

        val effVal = gate.getEffectiveValue(happyHourTimer > 0f)
        if (gate.isPositive()) {
            sound.playGateGood()
            runGoodGateStreak++
            prefs.setStatMax("max_good_gate_streak", runGoodGateStreak)
            crowd.applyGate(gate.op, effVal)
            spawnFloatingText(gate.formatLabel(happyHourTimer > 0f), crowd.centerX, screenH * 0.63f, Color.rgb(105, 240, 174))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(105, 240, 174), 14)
        } else {
            sound.playGateBad()
            runGoodGateStreak = 0
            runHitBadGate = true
            if (gate.op == Config.GateOp.DIV) {
                runHitDivGate = true
            }
            crowd.applyGate(gate.op, effVal)
            spawnFloatingText(gate.formatLabel(false), crowd.centerX, screenH * 0.63f, Color.rgb(255, 82, 82))
            spawnBurst(screenW * 0.5f + crowd.centerX * (screenW * 0.36f), screenH * 0.72f, Color.rgb(255, 82, 82), 14)
        }
        prefs.setStatMax("max_mugs_run", crowd.count)
    }

    private fun handleObstacleHit(obs: ObstacleItem) {
        if (crowd.hasShield) {
            crowd.hasShield = false
            sound.playShieldBlock()
            spawnFloatingText("Щит спас!", crowd.centerX, screenH * 0.63f, Color.rgb(129, 212, 250))
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
                spawnFloatingText("Пила! -$loss", crowd.centerX, screenH * 0.63f, Color.rgb(255, 82, 82))
                spawnBurst(px, py, Color.rgb(255, 138, 101), 16)
            }
            ObstacleType.PUDDLE -> {
                slowTimer = 1.4f
                val loss = max(1, (crowd.count * 0.10f).toInt())
                crowd.addCount(-loss)
                spawnFloatingText("Лужа! -$loss", crowd.centerX, screenH * 0.63f, Color.rgb(255, 202, 40))
                spawnBurst(px, py, Color.rgb(255, 179, 0), 12)
            }
            ObstacleType.CRATE -> {
                val loss = max(2, (crowd.count * 0.18f).toInt())
                crowd.addCount(-loss)
                spawnFloatingText("Ящик! -$loss", crowd.centerX, screenH * 0.63f, Color.rgb(255, 112, 67))
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
            spawnFloatingText("Щит раскидал врагов!", crowd.centerX, screenH * 0.61f, Color.rgb(129, 212, 250))
        }

        sound.playTrap()
        spawnBurst(px, py, Color.rgb(239, 83, 80), 18)

        if (crowd.count >= rivalCount) {
            crowd.addCount(-rivalCount)
            prefs.addStat("enemies_defeated", enemy.initialCount)
            spawnFloatingText("Победа! -$rivalCount", crowd.centerX, screenH * 0.64f, Color.rgb(255, 213, 79))
            checkAndShowAchievements()
        } else {
            crowd.setCount(0)
        }
    }

    private fun updateBossFight(dt: Float, data: LevelData, boss: Boss) {
        crowd.centerX += (0f - crowd.centerX) * (dt * 6f).coerceIn(0f, 1f)

        for (fm in flyingMugs) {
            if (fm.active) {
                fm.progress += dt * 4.2f
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
                    spawnFloatingText("Страж повержен! +$bonus м.", 0f, screenH * 0.45f, Color.rgb(255, 215, 0))
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

        // Фоновая синхронизация с GitHub Gist, если токен привязан
        if (prefs.githubToken.isNotEmpty()) {
            prefs.saveToGitHubGist { _, _ -> }
        }

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
                p.radius = 6f + rng.nextFloat() * 8f
                p.color = color
                p.life = 0.50f + rng.nextFloat() * 0.35f
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
                p.radius = 8f + rng.nextFloat() * 8f
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
                ft.life = 1.35f
                ft.maxLife = 1.35f
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
                ft.y -= 75f * dt
                ft.life -= dt
                if (ft.life <= 0f) ft.active = false
            }
        }
    }

    // ==================== BUTTON POOL HELPER ====================

    private fun registerButton(id: String, left: Float, top: Float, right: Float, bottom: Float) {
        if (buttonPoolIdx >= buttonPool.size) return
        val box = buttonPool[buttonPoolIdx++]
        box.id = id
        box.rect.set(left, top, right, bottom)
        stagingButtons.add(box)
    }

    // ==================== RENDERING (ZERO ALLOCATIONS) ====================

    private fun render(canvas: Canvas) {
        buttonPoolIdx = 0
        stagingButtons = if (readableButtons === buttonsA) buttonsB else buttonsA
        stagingButtons.clear()

        when (state) {
            ScreenState.MENU -> renderMenu(canvas)
            ScreenState.LEVEL_SELECT -> renderLevelSelect(canvas)
            ScreenState.SHOP -> renderShop(canvas)
            ScreenState.ACHIEVEMENTS -> renderAchievements(canvas)
            ScreenState.ACCOUNT -> renderAccount(canvas)
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

        // Атомарная публикация актуальных кнопок для UI-потока
        readableButtons = stagingButtons
    }

    private fun renderGameplay(canvas: Canvas) {
        val data = levelData ?: return
        val loc = data.location

        if (cachedSkyShader == null || cachedSkyTop != loc.skyTop || cachedSkyBottom != loc.skyBottom) {
            cachedSkyTop = loc.skyTop
            cachedSkyBottom = loc.skyBottom
            cachedSkyShader = LinearGradient(0f, 0f, 0f, screenH * 0.42f, loc.skyTop, loc.skyBottom, Shader.TileMode.CLAMP)
        }
        fillPaint.shader = cachedSkyShader
        canvas.drawRect(0f, 0f, screenW, screenH * 0.42f, fillPaint)
        fillPaint.shader = null

        drawLocationSkyDecor(canvas, loc)

        val horizonY = screenH * HORIZON_RATIO
        fillPaint.color = loc.sideColor
        canvas.drawRect(0f, horizonY, screenW, screenH, fillPaint)

        val bottomHalfW = screenW * 0.44f
        val topHalfW = screenW * 0.14f
        val cx = screenW * 0.5f

        tempPath.reset()
        tempPath.moveTo(cx - topHalfW, horizonY)
        tempPath.lineTo(cx + topHalfW, horizonY)
        tempPath.lineTo(cx + bottomHalfW, screenH)
        tempPath.lineTo(cx - bottomHalfW, screenH)
        tempPath.close()
        fillPaint.color = loc.trackMain
        canvas.drawPath(tempPath, fillPaint)

        val viewDist = VIEW_DIST
        val stripeStep = 140f
        val firstStripeIdx = (playerZ / stripeStep).toInt()
        for (idx in firstStripeIdx..(firstStripeIdx + 10)) {
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

            drawLocationRoadProp(canvas, loc, idx, cx - hw0 - 30f * p0, y0, p0)
            drawLocationRoadProp(canvas, loc, idx + 1, cx + hw0 + 30f * p0, y0, p0)
        }

        strokePaint.color = loc.railColor
        strokePaint.strokeWidth = 8f
        canvas.drawLine(cx - topHalfW, horizonY, cx - bottomHalfW, screenH, strokePaint)
        canvas.drawLine(cx + topHalfW, horizonY, cx + bottomHalfW, screenH, strokePaint)

        // Узкие мосты и лужи
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

                    fillPaint.color = Color.argb(185, 15, 20, 35)
                    canvas.drawRect(cx - hw1, y0, cx + hw1, y1, fillPaint)

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

        // Бонусные кружки и сундуки
        val mugSkin = Config.getMugSkin(prefs.selectedSkinId)
        for (pickup in data.pickups) {
            if (pickup.collected) continue
            val relZ = pickup.z - playerZ
            if (relZ in 0f..viewDist) {
                val p = projectDepth(relZ, viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val sx = cx + pickup.x * hw
                val itemSize = (54f * p).coerceAtLeast(16f)

                if (pickup.type == PickupType.BONUS_MUG) {
                    crowd.drawSingleMug(canvas, sx, sy, itemSize, itemSize * 1.35f, mugSkin, totalTimeSec)
                } else {
                    fillPaint.color = Color.rgb(141, 85, 36)
                    tempRect.set(sx - itemSize * 0.65f, sy - itemSize * 0.85f, sx + itemSize * 0.65f, sy)
                    canvas.drawRoundRect(tempRect, 8f, 8f, fillPaint)
                    fillPaint.color = Color.rgb(255, 215, 0)
                    canvas.drawRect(sx - itemSize * 0.65f, sy - itemSize * 0.52f, sx + itemSize * 0.65f, sy - itemSize * 0.36f, fillPaint)
                }
            }
        }

        // Пилы и ящики
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

        // Противники
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

        // Крупные хорошо читаемые рамки (Gates)
        val happyActive = happyHourTimer > 0f
        for (i in data.gateRows.size - 1 downTo 0) {
            val row = data.gateRows[i]
            if (row.triggered) continue
            val relZ = row.z - playerZ
            if (relZ in -10f..viewDist) {
                val p = projectDepth(relZ.coerceAtLeast(0f), viewDist)
                val sy = horizonY + (screenH - horizonY) * p
                val hw = topHalfW + (bottomHalfW - topHalfW) * p
                val gateH = (124f * p * uiScale * 0.88f).coerceAtLeast(28f)
                val offsetX = row.getOffsetX(totalTimeSec)

                for (g in row.gates) {
                    if (g.triggered) continue
                    val gx = cx + (g.laneCenter + offsetX) * hw
                    val gw = g.halfWidth * hw * 0.94f
                    val isGood = g.isPositive()

                    fillPaint.color = when {
                        isGood && happyActive -> Color.argb(205, 171, 71, 188)
                        isGood -> Color.argb(195, 0, 200, 83)
                        activeIceKeg -> Color.argb(195, 41, 182, 246)
                        else -> Color.argb(195, 229, 57, 53)
                    }
                    tempRect.set(gx - gw, sy - gateH, gx + gw, sy)
                    canvas.drawRoundRect(tempRect, 14f * p, 14f * p, fillPaint)

                    strokePaint.color = Color.WHITE
                    strokePaint.strokeWidth = (4.5f * p).coerceAtLeast(2f)
                    canvas.drawRoundRect(tempRect, 14f * p, 14f * p, strokePaint)

                    val label = g.formatLabel(happyActive)
                    val gFont = (40f * p * uiScale * 0.9f).coerceIn(18f, 58f)
                    textOutlinePaint.textSize = gFont
                    textOutlinePaint.strokeWidth = (gFont * 0.14f).coerceAtLeast(3f)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = gFont
                    val textY = sy - gateH * 0.30f
                    canvas.drawText(label, gx, textY, textOutlinePaint)
                    canvas.drawText(label, gx, textY, textPaint)
                }
            }
        }

        // Мини-босс и главный босс
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
            fillPaint.color = Color.WHITE
            val hw = topHalfW + (bottomHalfW - topHalfW) * p
            canvas.drawRect(cx - hw, sy - 10f * p, cx + hw, sy + 6f * p, fillPaint)
            data.mainBoss.draw(canvas, cx, sy, screenW * p.coerceAtLeast(0.38f), totalTimeSec)
        }

        // Толпа кружек игрока
        val playerBaseY = screenH * PLAYER_BASE_Y_RATIO
        val playerTrackHalfPx = topHalfW + (bottomHalfW - topHalfW) * projectDepth(CROWD_VISUAL_REL_Z, viewDist)
        crowd.draw(canvas, cx, playerBaseY, playerTrackHalfPx, mugSkin, totalTimeSec)

        for (fm in flyingMugs) {
            if (fm.active) {
                val t = fm.progress
                val fx = fm.startX + (fm.targetX - fm.startX) * t
                val fy = fm.startY + (fm.targetY - fm.startY) * t - sin(t * 3.14159f) * 140f
                crowd.drawSingleMug(canvas, fx, fy, 30f * uiScale, 42f * uiScale, mugSkin, totalTimeSec)
            }
        }

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
        val unitR = (16f * scale).coerceAtLeast(6f)
        val shown = count.coerceIn(1, 9)
        for (i in 0 until shown) {
            val col = (i % 3) - 1
            val row = i / 3
            val ex = cx + col * unitR * 1.65f
            val ey = bottomY - row * unitR * 1.1f
            fillPaint.color = Color.rgb(211, 47, 47)
            canvas.drawCircle(ex, ey - unitR * 0.9f, unitR * 0.75f, fillPaint)
            fillPaint.color = Color.rgb(255, 204, 170)
            canvas.drawCircle(ex, ey - unitR * 1.95f, unitR * 0.55f, fillPaint)
        }
        val badgeY = bottomY - unitR * 3.6f
        fillPaint.color = Color.rgb(183, 28, 28)
        val bw = 38f * scale.coerceAtLeast(0.55f)
        tempRect.set(cx - bw, badgeY - 18f, cx + bw, badgeY + 14f)
        canvas.drawRoundRect(tempRect, 12f, 12f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = (24f * scale).coerceIn(15f, 28f)
        canvas.drawText(count.toString(), cx, badgeY + 6f, textPaint)
    }

    private fun drawLocationSkyDecor(canvas: Canvas, loc: Config.LocationDef) {
        when (loc.decorType) {
            2, 3 -> {
                fillPaint.color = Color.argb(210, 255, 238, 88)
                canvas.drawCircle(screenW * 0.80f, screenH * 0.13f, 68f, fillPaint)
            }
            4 -> {
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
                fillPaint.color = Color.rgb(179, 229, 252)
                tempPath.reset()
                tempPath.moveTo(x, y - sz * 1.4f)
                tempPath.lineTo(x + sz * 0.5f, y)
                tempPath.lineTo(x - sz * 0.5f, y)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            3 -> {
                fillPaint.color = Color.rgb(56, 142, 60)
                canvas.drawRect(x - sz * 0.18f, y - sz * 1.3f, x + sz * 0.18f, y, fillPaint)
            }
            6 -> {
                fillPaint.color = Color.rgb(27, 94, 32)
                tempPath.reset()
                tempPath.moveTo(x, y - sz * 1.6f)
                tempPath.lineTo(x + sz * 0.65f, y)
                tempPath.lineTo(x - sz * 0.65f, y)
                tempPath.close()
                canvas.drawPath(tempPath, fillPaint)
            }
            else -> {
                if (seedIdx % 2 == 0) {
                    fillPaint.color = Color.rgb(109, 76, 65)
                    canvas.drawCircle(x, y - sz * 0.4f, sz * 0.42f, fillPaint)
                }
            }
        }
    }

    private fun renderGameplayHud(canvas: Canvas, data: LevelData) {
        val s = uiScale
        val topY = 38f * s
        val hudH = 74f * s
        val pauseSize = hudH

        // Крупная плашка уровня и локации
        fillPaint.color = Color.argb(190, 20, 22, 32)
        val lvlRight = screenW * 0.54f
        tempRect.set(20f * s, topY, lvlRight, topY + hudH)
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)

        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 23f * s
        canvas.drawText("Уровень ${data.levelNumber}", 38f * s, topY + 32f * s, leftTextPaint)
        subTextPaint.color = Color.rgb(255, 213, 79)
        subTextPaint.textSize = 17f * s
        canvas.drawText(data.location.name, 38f * s, topY + 58f * s, subTextPaint)

        // Крупная плашка монет
        val coinLeft = lvlRight + 12f * s
        val coinRight = screenW - 20f * s - pauseSize - 12f * s
        fillPaint.color = Color.argb(190, 20, 22, 32)
        tempRect.set(coinLeft, topY, coinRight, topY + hudH)
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 23f * s
        canvas.drawText("${prefs.coins}", tempRect.centerX(), topY + hudH * 0.62f, textPaint)

        // Большая кнопка паузы в правом верхнем углу
        val pauseLeft = screenW - 20f * s - pauseSize
        val pauseRight = screenW - 20f * s
        val pauseBottom = topY + pauseSize
        fillPaint.color = Color.argb(220, 38, 50, 56)
        tempRect.set(pauseLeft, topY, pauseRight, pauseBottom)
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f * s
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, strokePaint)

        fillPaint.color = Color.WHITE
        val pCx = tempRect.centerX()
        val pCy = tempRect.centerY()
        canvas.drawRect(pCx - 12f * s, pCy - 16f * s, pCx - 4f * s, pCy + 16f * s, fillPaint)
        canvas.drawRect(pCx + 4f * s, pCy - 16f * s, pCx + 12f * s, pCy + 16f * s, fillPaint)
        registerButton("pause", pauseLeft, topY, pauseRight, pauseBottom)

        // Полоса прогресса уровня
        val progY = topY + hudH + 14f * s
        val progH = 12f * s
        val prog = (playerZ / max(1f, data.trackLength - CROWD_VISUAL_REL_Z)).coerceIn(0f, 1f)
        fillPaint.color = Color.argb(150, 255, 255, 255)
        tempRect.set(screenW * 0.20f, progY, screenW * 0.80f, progY + progH)
        canvas.drawRoundRect(tempRect, progH * 0.5f, progH * 0.5f, fillPaint)
        fillPaint.color = Color.rgb(255, 193, 7)
        tempRect.set(screenW * 0.20f, progY, screenW * 0.20f + (screenW * 0.60f) * prog, progY + progH)
        canvas.drawRoundRect(tempRect, progH * 0.5f, progH * 0.5f, fillPaint)

        // Активные бонусы
        var badgeY = progY + progH + 14f * s
        if (happyHourTimer > 0f) {
            drawMiniHudTag(canvas, "Счастливый час: ${happyHourTimer.toInt() + 1}с", Color.rgb(171, 71, 188), badgeY)
            badgeY += 36f * s
        }
        if (activeIceKeg) {
            drawMiniHudTag(canvas, "Ледяной бочонок готов", Color.rgb(41, 182, 246), badgeY)
            badgeY += 36f * s
        }
        if (activeDoubleCoins) {
            drawMiniHudTag(canvas, "Золотой множитель x2", Color.rgb(255, 179, 0), badgeY)
        }
    }

    private fun drawMiniHudTag(canvas: Canvas, label: String, color: Int, y: Float) {
        val s = uiScale
        fillPaint.color = Color.argb(215, Color.red(color), Color.green(color), Color.blue(color))
        tempRect.set(20f * s, y, 280f * s, y + 30f * s)
        canvas.drawRoundRect(tempRect, 15f * s, 15f * s, fillPaint)
        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 16f * s
        canvas.drawText(label, 34f * s, y + 21f * s, leftTextPaint)
    }

    // ==================== MENU & SCREENS (LARGE UI) ====================

    private fun renderMenu(canvas: Canvas) {
        val s = uiScale

        fillPaint.color = Color.rgb(34, 22, 18)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        fillPaint.color = Color.argb(42, 255, 193, 7)
        canvas.drawCircle(screenW * 0.5f, screenH * 0.26f, screenW * 0.54f, fillPaint)

        // Верхняя панель: Монеты | Аккаунт / Облако | Звук
        val topY = 42f * s
        val pillH = 62f * s
        val coinRight = 236f * s
        fillPaint.color = Color.argb(205, 58, 40, 32)
        tempRect.set(24f * s, topY, coinRight, topY + pillH)
        canvas.drawRoundRect(tempRect, 31f * s, 31f * s, fillPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 21f * s
        canvas.drawText("Монеты: ${prefs.coins}", tempRect.centerX(), topY + pillH * 0.63f, textPaint)

        val muteLeft = screenW - 125f * s
        val muteRight = screenW - 24f * s
        fillPaint.color = Color.argb(205, 58, 40, 32)
        tempRect.set(muteLeft, topY, muteRight, topY + pillH)
        canvas.drawRoundRect(tempRect, 22f * s, 22f * s, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 20f * s
        canvas.drawText(if (sound.muted) "ВЫКЛ" else "ЗВУК", tempRect.centerX(), topY + pillH * 0.63f, textPaint)
        registerButton("toggle_mute", muteLeft, topY, muteRight, topY + pillH)

        // Кнопка привязки аккаунта / облака по центру верхней панели
        val accLeft = coinRight + 12f * s
        val accRight = muteLeft - 12f * s
        val isLinked = prefs.githubToken.isNotEmpty()
        fillPaint.color = if (isLinked) Color.rgb(32, 92, 62) else Color.rgb(44, 68, 96)
        tempRect.set(accLeft, topY, accRight, topY + pillH)
        canvas.drawRoundRect(tempRect, 24f * s, 24f * s, fillPaint)
        strokePaint.color = if (isLinked) Color.rgb(105, 240, 174) else Color.rgb(100, 181, 246)
        strokePaint.strokeWidth = 2.5f * s
        canvas.drawRoundRect(tempRect, 24f * s, 24f * s, strokePaint)

        val accLabel = when {
            prefs.githubUserLogin.isNotEmpty() -> "@${prefs.githubUserLogin}"
            isLinked -> "ОБЛАКО: ОК"
            else -> "АККАУНТ"
        }
        textPaint.color = Color.WHITE
        textPaint.textSize = 20f * s
        canvas.drawText(accLabel, tempRect.centerX(), topY + pillH * 0.63f, textPaint)
        registerButton("open_account", accLeft, topY, accRight, topY + pillH)

        // Крупный заголовок игры
        textPaint.color = Color.rgb(255, 193, 7)
        textPaint.textSize = 58f * s
        canvas.drawText("BEER RUN", screenW * 0.5f, screenH * 0.145f, textPaint)
        textPaint.color = Color.rgb(255, 248, 225)
        textPaint.textSize = 22f * s
        canvas.drawText("Пенный забег • Уровень ${prefs.level}", screenW * 0.5f, screenH * 0.182f, textPaint)

        // Превью кружки
        val selectedSkin = Config.getMugSkin(prefs.selectedSkinId)
        val previewBob = sin(totalTimeSec * 4f) * 8f
        crowd.drawSingleMug(
            canvas,
            screenW * 0.5f,
            screenH * 0.31f + previewBob,
            105f * s,
            145f * s,
            selectedSkin,
            totalTimeSec
        )

        // Крупные карточки бонусов в 2 колонки (6 штук: 2x3)
        textPaint.color = Color.rgb(235, 225, 210)
        textPaint.textSize = 19f * s
        canvas.drawText("Бонусы на забег (нажми чтобы взять):", screenW * 0.5f, screenH * 0.352f, textPaint)

        val boosters = Config.BoosterType.values()
        val pad = 24f * s
        val colGap = 14f * s
        val rowGap = 12f * s
        val cellW = (screenW - pad * 2f - colGap) / 2f
        val cellH = 66f * s
        val gridStartY = screenH * 0.368f

        for (i in boosters.indices) {
            val b = boosters[i]
            val col = i % 2
            val row = i / 2
            val bx = pad + col * (cellW + colGap)
            val by = gridStartY + row * (cellH + rowGap)
            val armed = prefs.isBoosterArmed(b)
            val cnt = prefs.getBoosterCount(b)

            fillPaint.color = when {
                armed -> Color.rgb(46, 125, 50)
                cnt > 0 -> Color.rgb(68, 50, 40)
                else -> Color.rgb(46, 35, 29)
            }
            tempRect.set(bx, by, bx + cellW, by + cellH)
            canvas.drawRoundRect(tempRect, 16f * s, 16f * s, fillPaint)
            strokePaint.color = if (armed) Color.rgb(105, 240, 174) else b.color
            strokePaint.strokeWidth = if (armed) 3.5f * s else 2f * s
            canvas.drawRoundRect(tempRect, 16f * s, 16f * s, strokePaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 18f * s
            canvas.drawText(b.title, tempRect.centerX(), by + 28f * s, textPaint)
            textPaint.color = if (cnt > 0) Color.rgb(255, 215, 0) else Color.rgb(165, 165, 175)
            textPaint.textSize = 17f * s
            val statusTxt = if (armed) "ВЗЯТО (x$cnt)" else "Запас: x$cnt"
            canvas.drawText(statusTxt, tempRect.centerX(), by + 53f * s, textPaint)

            registerButton("arm_booster:${b.id}", bx, by, bx + cellW, by + cellH)
        }

        // 4 большие кнопки главного меню
        val btnStartY = gridStartY + 3 * (cellH + rowGap) + 18f * s
        val btnW = screenW - 72f * s
        val btnX = 36f * s
        val btnH = 74f * s
        val gap = 16f * s

        drawMenuButton(canvas, "play_current", "ИГРАТЬ (Ур. ${prefs.level})", btnX, btnStartY, btnW, btnH, Color.rgb(255, 160, 0), Color.rgb(40, 20, 5))
        drawMenuButton(canvas, "open_levels", "ВЫБОР УРОВНЯ", btnX, btnStartY + (btnH + gap), btnW, btnH * 0.92f, Color.rgb(30, 136, 229), Color.WHITE)
        drawMenuButton(canvas, "open_shop", "МАГАЗИН", btnX, btnStartY + (btnH + gap) * 1.95f, btnW, btnH * 0.92f, Color.rgb(67, 160, 71), Color.WHITE)
        drawMenuButton(canvas, "open_achievements", "АЧИВКИ", btnX, btnStartY + (btnH + gap) * 2.90f, btnW, btnH * 0.92f, Color.rgb(142, 36, 170), Color.WHITE)
    }

    private fun renderAccount(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.rgb(22, 26, 38)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val pad = 24f * s
        val cardW = screenW - pad * 2f
        var curY = 122f * s

        // Карточка текущего прогресса и статуса операции
        val statusH = if (accountStatusText.isNotEmpty()) 112f * s else 80f * s
        tempRect.set(pad, curY, pad + cardW, curY + statusH)
        fillPaint.color = Color.rgb(34, 42, 60)
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)

        val unlockedAch = Config.ACHIEVEMENTS.count { prefs.isAchievementUnlocked(it.id) }
        textPaint.color = Color.WHITE
        textPaint.textSize = 21f * s
        canvas.drawText(
            "Прогресс: Ур. ${prefs.level} • ${prefs.coins} м. • Ачивок $unlockedAch/${Config.ACHIEVEMENTS.size}",
            screenW * 0.5f,
            curY + 42f * s,
            textPaint
        )
        if (accountStatusText.isNotEmpty()) {
            textPaint.color = accountStatusColor
            textPaint.textSize = 19f * s
            canvas.drawText(accountStatusText, screenW * 0.5f, curY + 86f * s, textPaint)
        }
        curY += statusH + 18f * s

        // Секция 1: Облако GitHub Gist
        val hasToken = prefs.githubToken.isNotEmpty()
        val ghBoxH = if (hasToken) 425f * s else 355f * s
        tempRect.set(pad, curY, pad + cardW, curY + ghBoxH)
        fillPaint.color = Color.rgb(32, 38, 54)
        canvas.drawRoundRect(tempRect, 22f * s, 22f * s, fillPaint)
        strokePaint.color = if (hasToken) Color.rgb(105, 240, 174) else Color.rgb(66, 165, 245)
        strokePaint.strokeWidth = 2.5f * s
        canvas.drawRoundRect(tempRect, 22f * s, 22f * s, strokePaint)

        leftTextPaint.color = Color.rgb(129, 212, 250)
        leftTextPaint.textSize = 24f * s
        canvas.drawText("1. Облако GitHub (Автосохранение)", pad + 22f * s, curY + 42f * s, leftTextPaint)

        subTextPaint.color = Color.rgb(210, 218, 230)
        subTextPaint.textSize = 18f * s
        val ghStatusLine = if (hasToken) {
            val userPart = if (prefs.githubUserLogin.isNotEmpty()) "@${prefs.githubUserLogin}" else "Токен привязан"
            val syncPart = if (prefs.lastCloudSync.isNotEmpty()) " • Синхр: ${prefs.lastCloudSync}" else ""
            "Аккаунт: $userPart$syncPart"
        } else {
            "Привяжи GitHub Token (право gist), чтобы не терять прогресс"
        }
        canvas.drawText(ghStatusLine, pad + 22f * s, curY + 74f * s, subTextPaint)

        val innerX = pad + 22f * s
        val innerW = cardW - 44f * s
        val btnH = 66f * s

        drawMenuButton(
            canvas,
            "gh_set_token",
            if (hasToken) "Изменить GitHub Token" else "Привязать GitHub Token",
            innerX,
            curY + 96f * s,
            innerW,
            btnH,
            Color.rgb(30, 136, 229),
            Color.WHITE
        )

        val cloudBtnAlpha = if (hasToken && !isCloudBusy) 255 else 120
        drawMenuButton(
            canvas,
            "gh_save",
            if (isCloudBusy) "Синхронизация..." else "Сохранить в GitHub",
            innerX,
            curY + 176f * s,
            innerW,
            btnH,
            Color.argb(cloudBtnAlpha, 46, 125, 50),
            Color.WHITE
        )

        drawMenuButton(
            canvas,
            "gh_load",
            "Загрузить из GitHub",
            innerX,
            curY + 256f * s,
            innerW,
            btnH,
            Color.argb(cloudBtnAlpha, 255, 160, 0),
            Color.rgb(35, 18, 5)
        )

        if (hasToken) {
            drawMenuButton(
                canvas,
                "gh_unlink",
                "Отвязать GitHub",
                innerX,
                curY + 336f * s,
                innerW,
                56f * s,
                Color.rgb(90, 42, 48),
                Color.rgb(255, 180, 180)
            )
        }

        curY += ghBoxH + 20f * s

        // Секция 2: Код переноса (работает без интернета)
        val codeBoxH = 264f * s
        tempRect.set(pad, curY, pad + cardW, curY + codeBoxH)
        fillPaint.color = Color.rgb(32, 38, 54)
        canvas.drawRoundRect(tempRect, 22f * s, 22f * s, fillPaint)
        strokePaint.color = Color.rgb(255, 193, 7)
        strokePaint.strokeWidth = 2.5f * s
        canvas.drawRoundRect(tempRect, 22f * s, 22f * s, strokePaint)

        leftTextPaint.color = Color.rgb(255, 213, 79)
        leftTextPaint.textSize = 24f * s
        canvas.drawText("2. Код переноса (Без интернета)", pad + 22f * s, curY + 42f * s, leftTextPaint)

        subTextPaint.color = Color.rgb(210, 218, 230)
        subTextPaint.textSize = 18f * s
        canvas.drawText("Сохрани код в заметки, чтобы восстановить игру в любой момент", pad + 22f * s, curY + 74f * s, subTextPaint)

        drawMenuButton(
            canvas,
            "code_copy",
            "Скопировать код сохранения",
            innerX,
            curY + 98f * s,
            innerW,
            btnH,
            Color.rgb(255, 160, 0),
            Color.rgb(35, 18, 5)
        )

        drawMenuButton(
            canvas,
            "code_paste",
            "Вставить код сохранения",
            innerX,
            curY + 178f * s,
            innerW,
            btnH,
            Color.rgb(142, 36, 170),
            Color.WHITE
        )

        renderTopHeader(canvas, "АККАУНТ")
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
        val s = uiScale
        tempRect.set(x, y, x + w, y + h)
        fillPaint.color = bgColor
        canvas.drawRoundRect(tempRect, 24f * s, 24f * s, fillPaint)
        strokePaint.color = Color.argb(135, 255, 255, 255)
        strokePaint.strokeWidth = 2.5f * s
        canvas.drawRoundRect(tempRect, 24f * s, 24f * s, strokePaint)

        textPaint.color = txtColor
        textPaint.textSize = 24f * s
        canvas.drawText(label, tempRect.centerX(), tempRect.centerY() + 8f * s, textPaint)
        registerButton(id, x, y, x + w, y + h)
    }

    private fun renderLevelSelect(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.rgb(24, 28, 42)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        renderTopHeader(canvas, "УРОВНИ")

        val maxUnlocked = prefs.maxUnlockedLevel
        val startLvl = levelPage * 20 + 1
        val cols = 4
        val pad = 24f * s
        val gap = 14f * s
        val cellSize = (screenW - pad * 2f - gap * (cols - 1)) / cols
        val gridTop = 135f * s

        for (i in 0 until 20) {
            val lvl = startLvl + i
            val col = i % cols
            val row = i / cols
            val x = pad + col * (cellSize + gap)
            val y = gridTop + row * (cellSize + gap)
            tempRect.set(x, y, x + cellSize, y + cellSize)
            val unlocked = lvl <= maxUnlocked
            val isBossLvl = (lvl % 5 == 0)

            fillPaint.color = when {
                !unlocked -> Color.rgb(45, 50, 65)
                isBossLvl -> Color.rgb(194, 24, 91)
                lvl == prefs.level -> Color.rgb(255, 160, 0)
                else -> Color.rgb(30, 136, 229)
            }
            canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)

            if (unlocked) {
                textPaint.color = Color.WHITE
                textPaint.textSize = 28f * s
                canvas.drawText("$lvl", tempRect.centerX(), tempRect.centerY() + 8f * s, textPaint)
                if (isBossLvl) {
                    textPaint.textSize = 14f * s
                    canvas.drawText("БОСС", tempRect.centerX(), tempRect.bottom - 12f * s, textPaint)
                }
                registerButton("select_level:$lvl", x, y, x + cellSize, y + cellSize)
            } else {
                strokePaint.color = Color.rgb(144, 164, 174)
                strokePaint.strokeWidth = 3.5f * s
                tempRect2.set(tempRect.centerX() - 12f * s, tempRect.centerY() - 22f * s, tempRect.centerX() + 12f * s, tempRect.centerY())
                canvas.drawArc(tempRect2, 180f, 180f, false, strokePaint)
                fillPaint.color = Color.rgb(144, 164, 174)
                tempRect2.set(tempRect.centerX() - 15f * s, tempRect.centerY() - 8f * s, tempRect.centerX() + 15f * s, tempRect.centerY() + 16f * s)
                canvas.drawRoundRect(tempRect2, 5f * s, 5f * s, fillPaint)
                textPaint.color = Color.rgb(176, 190, 197)
                textPaint.textSize = 16f * s
                canvas.drawText("$lvl", tempRect.centerX(), tempRect.bottom - 10f * s, textPaint)
            }
        }

        val navY = gridTop + 5 * (cellSize + gap) + 20f * s
        val navW = 200f * s
        val navH = 66f * s
        if (levelPage > 0) {
            drawMenuButton(canvas, "lvl_prev_page", "< Назад", pad, navY, navW, navH, Color.rgb(69, 90, 100), Color.WHITE)
        }
        drawMenuButton(canvas, "lvl_next_page", "Вперёд >", screenW - pad - navW, navY, navW, navH, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderShop(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.rgb(26, 22, 34)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val tabs = ShopTab.values()
        val tabW = (screenW - 36f * s) / tabs.size
        val tabTop = 114f * s
        val tabH = 54f * s
        for (i in tabs.indices) {
            val t = tabs[i]
            val tx = 18f * s + i * tabW
            val left = tx + 3f * s
            val right = tx + tabW - 3f * s
            tempRect.set(left, tabTop, right, tabTop + tabH)
            val active = (t == currentShopTab)
            fillPaint.color = if (active) Color.rgb(255, 160, 0) else Color.rgb(52, 46, 66)
            canvas.drawRoundRect(tempRect, 14f * s, 14f * s, fillPaint)
            textPaint.color = if (active) Color.rgb(30, 15, 5) else Color.WHITE
            textPaint.textSize = 18f * s
            canvas.drawText(t.title, tempRect.centerX(), tempRect.centerY() + 6f * s, textPaint)
            registerButton("shop_tab:${t.name}", left, tabTop, right, tabTop + tabH)
        }

        val listTop = 180f * s
        canvas.save()
        canvas.clipRect(0f, listTop, screenW, screenH)

        var curY = listTop + 10f * s - scrollOffsetY
        val cardH = 114f * s
        val cardGap = 14f * s

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
                        "За ачивку «$achName»"
                    } else {
                        "Скин кружки"
                    }
                    drawShopCard(canvas, curY, cardH, listTop, skin.name, sub, btnLabel, btnColor, "buy_mug:${skin.id}") { cx, cy ->
                        crowd.drawSingleMug(canvas, cx, cy + 28f * s, 44f * s, 60f * s, skin, totalTimeSec)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BOSSES -> {
                val autoSel = prefs.selectedBossId == "auto"
                drawShopCard(
                    canvas, curY, cardH, listTop,
                    "По уровню (Авто)", "Смена каждый уровень",
                    if (autoSel) "Выбрано" else "Выбрать",
                    if (autoSel) Color.rgb(46, 125, 50) else Color.rgb(30, 136, 229),
                    "buy_boss:auto"
                ) { cx, cy ->
                    fillPaint.color = Color.rgb(255, 193, 7)
                    canvas.drawCircle(cx, cy, 24f * s, fillPaint)
                    textPaint.color = Color.BLACK
                    textPaint.textSize = 18f * s
                    canvas.drawText("AUTO", cx, cy + 6f * s, textPaint)
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
                    drawShopCard(canvas, curY, cardH, listTop, boss.name, "Облик босса", btnLabel, btnColor, "buy_boss:${boss.id}") { cx, cy ->
                        fillPaint.color = boss.outfitColor
                        canvas.drawCircle(cx, cy + 10f * s, 22f * s, fillPaint)
                        fillPaint.color = boss.skinColor
                        canvas.drawCircle(cx, cy - 13f * s, 16f * s, fillPaint)
                        fillPaint.color = boss.accentColor
                        canvas.drawCircle(cx, cy - 26f * s, 8f * s, fillPaint)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BOOSTERS -> {
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
                        canvas, curY, cardH, listTop,
                        "${upg.title} (${lvl}/${upg.maxLevel})",
                        upg.desc,
                        btnLabel, btnColor,
                        "buy_upgrade:${upg.id}"
                    ) { cx, cy ->
                        fillPaint.color = Color.rgb(255, 215, 0)
                        canvas.drawCircle(cx, cy, 24f * s, fillPaint)
                        textPaint.color = Color.rgb(40, 20, 5)
                        textPaint.textSize = 20f * s
                        canvas.drawText("UP", cx, cy + 7f * s, textPaint)
                    }
                    curY += cardH + cardGap
                }

                for (b in Config.BoosterType.values()) {
                    val cnt = prefs.getBoosterCount(b)
                    val btnColor = if (prefs.coins >= b.price) Color.rgb(255, 160, 0) else Color.rgb(90, 90, 100)
                    drawShopCard(
                        canvas, curY, cardH, listTop,
                        "${b.title} (x$cnt)",
                        b.desc,
                        "${b.price} м.", btnColor,
                        "buy_booster:${b.id}"
                    ) { cx, cy ->
                        fillPaint.color = b.color
                        canvas.drawCircle(cx, cy, 24f * s, fillPaint)
                        textPaint.color = Color.WHITE
                        textPaint.textSize = 20f * s
                        canvas.drawText("+1", cx, cy + 7f * s, textPaint)
                    }
                    curY += cardH + cardGap
                }
            }
            ShopTab.BACKGROUNDS -> {
                val autoSel = prefs.selectedLocationId == "auto"
                drawShopCard(
                    canvas, curY, cardH, listTop,
                    "Смена каждые 3 уровня", "Авто-путешествие",
                    if (autoSel) "Выбрано" else "Выбрать",
                    if (autoSel) Color.rgb(46, 125, 50) else Color.rgb(30, 136, 229),
                    "buy_loc:auto"
                ) { cx, cy ->
                    fillPaint.color = Color.rgb(0, 188, 212)
                    canvas.drawCircle(cx, cy, 24f * s, fillPaint)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = 18f * s
                    canvas.drawText("MIX", cx, cy + 6f * s, textPaint)
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
                    drawShopCard(canvas, curY, cardH, listTop, loc.name, "Локация и трасса", btnLabel, btnColor, "buy_loc:${loc.id}") { cx, cy ->
                        fillPaint.color = loc.skyTop
                        canvas.drawCircle(cx, cy, 26f * s, fillPaint)
                        fillPaint.color = loc.trackMain
                        canvas.drawCircle(cx, cy + 10f * s, 14f * s, fillPaint)
                    }
                    curY += cardH + cardGap
                }
            }
        }

        val totalContentH = (curY + scrollOffsetY) - listTop
        maxScrollY = (totalContentH - (screenH - listTop) + 40f * s).coerceAtLeast(0f)

        canvas.restore()
        renderTopHeader(canvas, "МАГАЗИН")
    }

    private inline fun drawShopCard(
        canvas: Canvas,
        y: Float,
        h: Float,
        listTop: Float,
        title: String,
        subtitle: String,
        btnLabel: String,
        btnColor: Int,
        actionId: String,
        drawPreview: (Float, Float) -> Unit
    ) {
        val s = uiScale
        if (y + h < listTop || y > screenH) return
        tempRect.set(18f * s, y, screenW - 18f * s, y + h)
        fillPaint.color = Color.rgb(42, 37, 56)
        canvas.drawRoundRect(tempRect, 18f * s, 18f * s, fillPaint)

        val iconCx = 66f * s
        fillPaint.color = Color.rgb(30, 26, 40)
        canvas.drawCircle(iconCx, y + h * 0.5f, 34f * s, fillPaint)
        drawPreview(iconCx, y + h * 0.5f)

        leftTextPaint.color = Color.WHITE
        leftTextPaint.textSize = 20f * s
        canvas.drawText(title, 114f * s, y + 44f * s, leftTextPaint)

        subTextPaint.color = Color.rgb(195, 200, 215)
        subTextPaint.textSize = 16f * s
        canvas.drawText(subtitle, 114f * s, y + 76f * s, subTextPaint)

        val btnLeft = screenW - 172f * s
        val btnRight = screenW - 32f * s
        val btnTop = y + 24f * s
        val btnBottom = y + h - 24f * s
        tempRect2.set(btnLeft, btnTop, btnRight, btnBottom)
        fillPaint.color = btnColor
        canvas.drawRoundRect(tempRect2, 15f * s, 15f * s, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 19f * s
        canvas.drawText(btnLabel, tempRect2.centerX(), tempRect2.centerY() + 7f * s, textPaint)

        if (btnBottom > listTop) {
            registerButton(actionId, btnLeft, max(btnTop, listTop), btnRight, btnBottom)
        }
    }

    private fun renderAchievements(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.rgb(22, 26, 38)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val listTop = 114f * s
        canvas.save()
        canvas.clipRect(0f, listTop, screenW, screenH)

        var curY = listTop + 10f * s - scrollOffsetY
        val cardH = 110f * s
        val gap = 12f * s

        for (ach in Config.ACHIEVEMENTS) {
            val unlocked = prefs.isAchievementUnlocked(ach.id)
            val progress = prefs.getStat(ach.statKey).coerceAtMost(ach.target)
            if (curY + cardH >= listTop && curY <= screenH) {
                tempRect.set(18f * s, curY, screenW - 18f * s, curY + cardH)
                fillPaint.color = if (unlocked) Color.rgb(35, 68, 52) else Color.rgb(38, 44, 62)
                canvas.drawRoundRect(tempRect, 18f * s, 18f * s, fillPaint)

                fillPaint.color = if (unlocked) Color.rgb(105, 240, 174) else Color.rgb(84, 110, 122)
                canvas.drawCircle(58f * s, curY + cardH * 0.5f, 22f * s, fillPaint)
                textPaint.color = Color.rgb(20, 30, 25)
                textPaint.textSize = 22f * s
                canvas.drawText(if (unlocked) "✓" else "★", 58f * s, curY + cardH * 0.5f + 8f * s, textPaint)

                leftTextPaint.color = Color.WHITE
                leftTextPaint.textSize = 20f * s
                canvas.drawText(ach.title, 96f * s, curY + 35f * s, leftTextPaint)

                subTextPaint.color = Color.rgb(200, 208, 220)
                subTextPaint.textSize = 16f * s
                canvas.drawText(ach.desc, 96f * s, curY + 60f * s, subTextPaint)

                val barLeft = 96f * s
                val barRight = screenW - 152f * s
                val barTop = curY + 74f * s
                val barBottom = curY + 92f * s
                fillPaint.color = Color.argb(140, 0, 0, 0)
                tempRect.set(barLeft, barTop, barRight, barBottom)
                canvas.drawRoundRect(tempRect, 9f * s, 9f * s, fillPaint)

                val ratio = (progress.toFloat() / max(1, ach.target)).coerceIn(0f, 1f)
                fillPaint.color = if (unlocked) Color.rgb(105, 240, 174) else Color.rgb(255, 193, 7)
                tempRect.set(barLeft, barTop, barLeft + (barRight - barLeft) * ratio, barBottom)
                canvas.drawRoundRect(tempRect, 9f * s, 9f * s, fillPaint)

                textPaint.color = Color.rgb(255, 215, 0)
                textPaint.textSize = 18f * s
                canvas.drawText("+${ach.rewardCoins} м.", screenW - 84f * s, curY + 46f * s, textPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 16f * s
                canvas.drawText("$progress/${ach.target}", screenW - 84f * s, curY + 88f * s, textPaint)
            }
            curY += cardH + gap
        }

        val totalH = (curY + scrollOffsetY) - listTop
        maxScrollY = (totalH - (screenH - listTop) + 40f * s).coerceAtLeast(0f)

        canvas.restore()
        renderTopHeader(canvas, "АЧИВКИ (${Config.ACHIEVEMENTS.count { prefs.isAchievementUnlocked(it.id) }}/${Config.ACHIEVEMENTS.size})")
    }

    private fun renderTopHeader(canvas: Canvas, title: String) {
        val s = uiScale
        fillPaint.color = Color.rgb(18, 20, 30)
        canvas.drawRect(0f, 0f, screenW, 106f * s, fillPaint)

        val backLeft = 18f * s
        val backTop = 32f * s
        val backRight = 148f * s
        val backBottom = 94f * s
        tempRect.set(backLeft, backTop, backRight, backBottom)
        fillPaint.color = Color.rgb(55, 65, 85)
        canvas.drawRoundRect(tempRect, 16f * s, 16f * s, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 20f * s
        canvas.drawText("< Меню", tempRect.centerX(), tempRect.centerY() + 7f * s, textPaint)
        registerButton("back_menu", backLeft, backTop, backRight, backBottom)

        textPaint.color = Color.WHITE
        textPaint.textSize = 24f * s
        canvas.drawText(title, screenW * 0.54f, 70f * s, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 20f * s
        canvas.drawText("${prefs.coins} м.", screenW - 64f * s, 70f * s, textPaint)
    }

    private fun renderPauseOverlay(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.argb(190, 10, 12, 20)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 72f * s
        val panelH = 400f * s
        val px = 36f * s
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(36, 40, 56)
        canvas.drawRoundRect(tempRect, 26f * s, 26f * s, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 36f * s
        canvas.drawText("ПАУЗА", screenW * 0.5f, py + 66f * s, textPaint)

        val btnW = panelW - 56f * s
        val btnH = 72f * s
        drawMenuButton(canvas, "pause_resume", "Продолжить", px + 28f * s, py + 104f * s, btnW, btnH, Color.rgb(67, 160, 71), Color.WHITE)
        drawMenuButton(canvas, "pause_restart", "Заново", px + 28f * s, py + 194f * s, btnW, btnH, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "pause_menu", "В главное меню", px + 28f * s, py + 284f * s, btnW, btnH, Color.rgb(229, 57, 53), Color.WHITE)
    }

    private fun renderWinOverlay(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.argb(180, 12, 24, 18)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 64f * s
        val panelH = 420f * s
        val px = 32f * s
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(30, 52, 42)
        canvas.drawRoundRect(tempRect, 26f * s, 26f * s, fillPaint)

        textPaint.color = Color.rgb(105, 240, 174)
        textPaint.textSize = 40f * s
        canvas.drawText("ПОБЕДА!", screenW * 0.5f, py + 70f * s, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 22f * s
        canvas.drawText("Босс повержен! Осталось кружек: ${crowd.count}", screenW * 0.5f, py + 120f * s, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 27f * s
        canvas.drawText("Награда: +$runCoinsEarned монет", screenW * 0.5f, py + 166f * s, textPaint)

        val btnW = panelW - 56f * s
        drawMenuButton(canvas, "win_next", "Следующий уровень", px + 28f * s, py + 212f * s, btnW, 76f * s, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "win_menu", "В главное меню", px + 28f * s, py + 306f * s, btnW, 70f * s, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderLoseOverlay(canvas: Canvas) {
        val s = uiScale
        fillPaint.color = Color.argb(190, 28, 12, 14)
        canvas.drawRect(0f, 0f, screenW, screenH, fillPaint)

        val panelW = screenW - 64f * s
        val panelH = 390f * s
        val px = 32f * s
        val py = (screenH - panelH) * 0.5f
        tempRect.set(px, py, px + panelW, py + panelH)
        fillPaint.color = Color.rgb(54, 28, 34)
        canvas.drawRoundRect(tempRect, 26f * s, 26f * s, fillPaint)

        textPaint.color = Color.rgb(255, 82, 82)
        textPaint.textSize = 36f * s
        canvas.drawText("НЕ ХВАТИЛО ПЕНЫ!", screenW * 0.5f, py + 70f * s, textPaint)

        textPaint.color = Color.rgb(230, 220, 220)
        textPaint.textSize = 20f * s
        canvas.drawText("Собирай больше кружек через x и +", screenW * 0.5f, py + 120f * s, textPaint)

        val btnW = panelW - 56f * s
        drawMenuButton(canvas, "lose_retry", "Попробовать заново", px + 28f * s, py + 178f * s, btnW, 76f * s, Color.rgb(255, 160, 0), Color.rgb(35, 18, 5))
        drawMenuButton(canvas, "lose_menu", "В главное меню", px + 28f * s, py + 272f * s, btnW, 70f * s, Color.rgb(69, 90, 100), Color.WHITE)
    }

    private fun renderParticlesAndTexts(canvas: Canvas) {
        val s = uiScale
        for (p in particles) {
            if (p.active) {
                val alpha = ((p.life / p.maxLife) * 255).toInt().coerceIn(0, 255)
                fillPaint.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                canvas.drawCircle(p.x, p.y, p.radius * s * 0.85f, fillPaint)
            }
        }
        val fSize = 27f * s
        textOutlinePaint.textSize = fSize
        textOutlinePaint.strokeWidth = 4.5f * s
        textPaint.textSize = fSize
        for (ft in floatingTexts) {
            if (ft.active) {
                val alpha = ((ft.life / ft.maxLife) * 255).toInt().coerceIn(0, 255)
                textOutlinePaint.color = Color.argb(alpha, 15, 15, 20)
                textPaint.color = Color.argb(alpha, Color.red(ft.color), Color.green(ft.color), Color.blue(ft.color))
                canvas.drawText(ft.text, ft.x, ft.y, textOutlinePaint)
                canvas.drawText(ft.text, ft.x, ft.y, textPaint)
            }
        }
    }

    private fun renderAchievementBanner(canvas: Canvas) {
        if (bannerTimer <= 0f) return
        val s = uiScale
        val slide = min(1f, bannerTimer * 3f) * min(1f, (3.5f - bannerTimer) * 5f + 0.2f)
        val bannerH = 82f * s
        val top = -bannerH - 10f + (bannerH + 34f * s) * slide
        tempRect.set(28f * s, top, screenW - 28f * s, top + bannerH)
        fillPaint.color = Color.rgb(46, 125, 50)
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, fillPaint)
        strokePaint.color = Color.rgb(255, 215, 0)
        strokePaint.strokeWidth = 3f * s
        canvas.drawRoundRect(tempRect, 20f * s, 20f * s, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 21f * s
        canvas.drawText(bannerTitle, screenW * 0.5f, top + 34f * s, textPaint)
        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = 18f * s
        canvas.drawText(bannerReward, screenW * 0.5f, top + 62f * s, textPaint)
    }

    // ==================== DIALOGS & CLIPBOARD ====================

    private fun showGitHubTokenDialog() {
        post {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 28, 48, 12)
            }
            val hintView = TextView(context).apply {
                text = "Вставь GitHub Personal Access Token с правом 'gist'.\nИгра сама создаст закрытый Gist на твоём аккаунте."
                textSize = 14f
                setPadding(0, 0, 0, 20)
            }
            val input = EditText(context).apply {
                hint = "ghp_... или github_pat_..."
                setText(prefs.githubToken)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine(true)
            }
            container.addView(hintView)
            container.addView(input)

            AlertDialog.Builder(context)
                .setTitle("Привязка GitHub Gist")
                .setView(container)
                .setPositiveButton("Сохранить токен") { _, _ ->
                    val token = input.text.toString().trim()
                    if (token.isNotEmpty()) {
                        prefs.githubToken = token
                        prefs.githubGistId = ""
                        accountStatusColor = Color.rgb(129, 212, 250)
                        accountStatusText = "Токен сохранён! Нажми «Сохранить» или «Загрузить»"
                    }
                }
                .setNeutralButton("Создать токен") { _, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?description=BeerRunSave&scopes=gist"))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        accountStatusColor = Color.rgb(255, 202, 40)
                        accountStatusText = "Открой github.com/settings/tokens и выбери право gist"
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun copyTransferCodeToClipboard() {
        post {
            try {
                val code = prefs.exportTransferCode()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BeerRunSave", code))
                accountStatusColor = Color.rgb(105, 240, 174)
                accountStatusText = "Код скопирован в буфер! Сохрани его в заметки"
            } catch (e: Exception) {
                accountStatusColor = Color.rgb(255, 82, 82)
                accountStatusText = "Ошибка копирования кода"
            }
        }
    }

    private fun showPasteCodeDialog() {
        post {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 28, 48, 12)
            }
            val hintView = TextView(context).apply {
                text = "Вставь код сохранения (начинается с BEER1:):"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            }
            val input = EditText(context).apply {
                hint = "BEER1:..."
                minLines = 3
                maxLines = 6
            }

            // Если в буфере обмена уже есть код сохранения, подставляем его автоматически
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (clipText.startsWith("BEER1:") || clipText.startsWith("{")) {
                    input.setText(clipText)
                }
            } catch (_: Exception) {
            }

            container.addView(hintView)
            container.addView(input)

            AlertDialog.Builder(context)
                .setTitle("Восстановление по коду")
                .setView(container)
                .setPositiveButton("Восстановить") { _, _ ->
                    val res = prefs.importTransferCode(input.text.toString())
                    if (res.isSuccess) {
                        levelPage = (prefs.level - 1) / 20
                        sound.playWin()
                        accountStatusColor = Color.rgb(105, 240, 174)
                        accountStatusText = "Восстановлено: ${res.getOrNull()}"
                    } else {
                        sound.playGateBad()
                        accountStatusColor = Color.rgb(255, 82, 82)
                        accountStatusText = res.exceptionOrNull()?.message ?: "Неверный код"
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    // ==================== INSTANT INPUT HANDLING ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                touchMovedDist = 0f
                isDraggingScroll = false

                // Мгновенный клик на ACTION_DOWN без задержки на отпускание пальца!
                val isScrollableScreen = (state == ScreenState.SHOP || state == ScreenState.ACHIEVEMENTS)
                if (!isScrollableScreen || y < 180f * uiScale) {
                    handleTap(x, y)
                }
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
                    if (touchMovedDist > 24f * uiScale) {
                        isDraggingScroll = true
                        scrollOffsetY = (scrollOffsetY - dy).coerceIn(0f, maxScrollY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val isScrollableScreen = (state == ScreenState.SHOP || state == ScreenState.ACHIEVEMENTS)
                if (isScrollableScreen && y >= 180f * uiScale && !isDraggingScroll && touchMovedDist < 42f * uiScale) {
                    handleTap(x, y)
                }
                isDraggingScroll = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float): Boolean {
        val snapshot = readableButtons
        for (i in snapshot.size - 1 downTo 0) {
            val btn = snapshot[i]
            if (btn.rect.contains(x, y)) {
                sound.playClick()
                executeButtonAction(btn.id)
                return true
            }
        }
        return false
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
            id == "open_account" -> {
                accountStatusText = ""
                state = ScreenState.ACCOUNT
            }
            id == "gh_set_token" -> {
                showGitHubTokenDialog()
            }
            id == "gh_save" -> {
                if (isCloudBusy) return
                isCloudBusy = true
                accountStatusColor = Color.rgb(129, 212, 250)
                accountStatusText = "Отправка сохранения на GitHub..."
                prefs.saveToGitHubGist { ok, msg ->
                    isCloudBusy = false
                    accountStatusColor = if (ok) Color.rgb(105, 240, 174) else Color.rgb(255, 82, 82)
                    accountStatusText = msg
                    if (ok) sound.playPickup() else sound.playGateBad()
                }
            }
            id == "gh_load" -> {
                if (isCloudBusy) return
                isCloudBusy = true
                accountStatusColor = Color.rgb(129, 212, 250)
                accountStatusText = "Поиск сохранения на GitHub..."
                prefs.loadFromGitHubGist { ok, msg ->
                    isCloudBusy = false
                    accountStatusColor = if (ok) Color.rgb(105, 240, 174) else Color.rgb(255, 82, 82)
                    accountStatusText = msg
                    if (ok) {
                        levelPage = (prefs.level - 1) / 20
                        sound.playWin()
                    } else {
                        sound.playGateBad()
                    }
                }
            }
            id == "gh_unlink" -> {
                prefs.clearGitHubLink()
                accountStatusColor = Color.rgb(255, 202, 40)
                accountStatusText = "GitHub отвязан от этого устройства"
            }
            id == "code_copy" -> {
                copyTransferCodeToClipboard()
            }
            id == "code_paste" -> {
                showPasteCodeDialog()
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
