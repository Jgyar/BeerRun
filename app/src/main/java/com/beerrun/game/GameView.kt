package com.beerrun.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Основной игровой экран на SurfaceView: 60 FPS игровой цикл, псевдо-3D дорожка,
 * управление одним пальцем, бой с боссом, магазин скинов и экраны WIN/LOSE.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class GameState {
        MENU,
        PLAYING,
        BOSS_FIGHT,
        WIN,
        LOSE
    }

    private val prefs = Prefs(context)
    private val sound = Sound(context)
    private val crowd = Crowd()
    private val boss = Boss()
    private val worldPaints = WorldPaints()

    private var gameThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var state = GameState.MENU

    private var screenW = 0
    private var screenH = 0
    private var horizonY = 0f
    private var playerY = 0f
    private var roadHalfWidth = 0f

    private lateinit var currentLevel: LevelData
    private var distanceTraveled = 0f
    private var runSpeed = Config.BASE_RUN_SPEED
    private var earnedCoinsThisRun = 0

    // Параметры подачи кружек в рот боссу
    private var mugLaunchTimer = 0f
    private var mugLaunchInterval = 0.04f

    // Система частиц и летящих к боссу кружек (примитивные массивы без аллокаций в onDraw)
    private val maxParticles = Config.MAX_PARTICLES
    private val pActive = BooleanArray(maxParticles)
    private val pX = FloatArray(maxParticles)
    private val pY = FloatArray(maxParticles)
    private val pVx = FloatArray(maxParticles)
    private val pVy = FloatArray(maxParticles)
    private val pSize = FloatArray(maxParticles)
    private val pLife = FloatArray(maxParticles)
    private val pMaxLife = FloatArray(maxParticles)
    private val pColor = IntArray(maxParticles)
    private val pIsConfetti = BooleanArray(maxParticles)

    private val maxFlyingMugs = 36
    private val fActive = BooleanArray(maxFlyingMugs)
    private val fStartX = FloatArray(maxFlyingMugs)
    private val fStartY = FloatArray(maxFlyingMugs)
    private val fProgress = FloatArray(maxFlyingMugs)

    // Управление пальцем
    private var lastTouchX = 0f
    private var isDragging = false

    // Кнопки интерфейса (пересчитываются в onSizeChanged)
    private val shopCardRects = Array(3) { RectF() }
    private val actionButtonRect = RectF()
    private val tmpRect = RectF()
    private val roadPath = Path()

    // Предвыделенные кисти фона и HUD
    private val skyPaint = Paint()
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val overlayBgPaint = Paint().apply { color = Color.argb(165, 13, 22, 45) }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hudFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A237E")
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        holder.addCallback(this)
        isFocusable = true
        crowd.activeSkin = Config.Skin.fromId(prefs.selectedSkinId)
        loadLevel(prefs.level, toMenu = true)
    }

    private fun loadLevel(levelNum: Int, toMenu: Boolean) {
        currentLevel = LevelGenerator.generate(levelNum)
        distanceTraveled = 0f
        runSpeed = (Config.BASE_RUN_SPEED + (levelNum - 1) * Config.SPEED_PER_LEVEL)
            .coerceAtMost(Config.MAX_RUN_SPEED)
        crowd.reset(Config.START_MUGS)
        boss.reset(currentLevel.length, currentLevel.bossThirst)
        clearEffects()
        state = if (toMenu) GameState.MENU else GameState.PLAYING
    }

    private fun clearEffects() {
        for (i in 0 until maxParticles) pActive[i] = false
        for (i in 0 until maxFlyingMugs) fActive[i] = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        gameThread = Thread(this, "BeerRunLoop").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateDimensions(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            gameThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        gameThread = null
    }

    fun onHostPause() {
        running = false
        try {
            gameThread?.join(800)
        } catch (_: InterruptedException) {
        }
        gameThread = null
    }

    fun onHostResume() {
        if (holder.surface.isValid && !running) {
            running = true
            gameThread = Thread(this, "BeerRunLoop").also { it.start() }
        }
    }

    fun onHostDestroy() {
        sound.release()
    }

    private fun updateDimensions(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        screenW = w
        screenH = h
        horizonY = h * Config.HORIZON_Y_RATIO
        playerY = h * Config.PLAYER_Y_RATIO
        roadHalfWidth = w * Config.ROAD_HALF_WIDTH_RATIO

        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            Config.COLOR_SKY_TOP, Config.COLOR_SKY_BOTTOM,
            Shader.TileMode.CLAMP
        )

        // Карточки магазина скинов внизу экрана меню
        val cardMargin = w * 0.04f
        val totalGap = cardMargin * 4f
        val cardW = (w - totalGap) / 3f
        val cardH = h * 0.16f
        val cardTop = h * 0.78f
        for (i in 0 until 3) {
            val left = cardMargin + i * (cardW + cardMargin)
            shopCardRects[i].set(left, cardTop, left + cardW, cardTop + cardH)
        }

        // Центральная кнопка "ДАЛЬШЕ" / "ЗАНОВО"
        val btnW = w * 0.62f
        val btnH = h * 0.085f
        actionButtonRect.set(
            w * 0.5f - btnW * 0.5f,
            h * 0.60f,
            w * 0.5f + btnW * 0.5f,
            h * 0.60f + btnH
        )
    }

    override fun run() {
        var lastTimeNanos = System.nanoTime()
        val targetFrameNanos = 1_000_000_000L / 60L

        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastTimeNanos = now

            update(dt)
            render()

            val elapsed = System.nanoTime() - now
            val sleepMillis = (targetFrameNanos - elapsed) / 1_000_000L
            if (sleepMillis > 1L) {
                try {
                    Thread.sleep(sleepMillis)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun update(dt: Float) {
        if (screenW == 0 || screenH == 0) return

        updateParticles(dt)
        boss.update(dt)

        when (state) {
            GameState.MENU -> {
                crowd.update(dt, isRunning = false)
            }

            GameState.PLAYING -> {
                val step = runSpeed * dt
                distanceTraveled += step
                crowd.update(dt, isRunning = true)

                // Обновление гейтов и проверка прохождения
                for (gate in currentLevel.gates) {
                    gate.z -= step
                    if (!gate.triggered && gate.z <= 0f) {
                        gate.triggered = true
                        gate.triggeredLeft = crowd.centerX < 0f
                        val chosen = if (gate.triggeredLeft) gate.left else gate.right
                        val oldCount = crowd.count
                        val newCount = chosen.apply(oldCount)
                        crowd.setCount(newCount)

                        val splashX = screenW * 0.5f + crowd.centerX * roadHalfWidth
                        if (chosen.isPositive) {
                            sound.playGateGood()
                            spawnBeerSplash(splashX, playerY - 40f, 26, isFoam = true)
                        } else {
                            sound.playGateBad()
                            spawnBeerSplash(splashX, playerY - 40f, 26, isFoam = false)
                        }

                        if (crowd.count <= 0) {
                            triggerLose()
                            return
                        }
                    }
                }

                // Обновление ловушек и уничтожение задетых кружек
                for (trap in currentLevel.traps) {
                    trap.z -= step
                    trap.update(dt)
                    if (trap.z in -6f..8f && trap.hitCooldown <= 0f && crowd.count > 0) {
                        var hitAny = false
                        val visCount = crowd.getVisualCount()
                        for (i in 0 until visCount) {
                            val dx = abs(crowd.getVisualMugX(i) - trap.x)
                            val dz = abs(crowd.getVisualMugZ(i) - trap.z)
                            if (dx < trap.radius && dz < 4.5f) {
                                hitAny = true
                                break
                            }
                        }
                        if (hitAny) {
                            trap.hitCooldown = if (trap.type == TrapType.SPIKES) {
                                Config.SPIKE_HIT_INTERVAL
                            } else {
                                Config.PUDDLE_HIT_INTERVAL
                            }
                            // При большой толпе ловушка снимает пропорционально больше за тик
                            val loss = (crowd.count / 35).coerceAtLeast(1)
                            crowd.setCount(crowd.count - loss)
                            sound.playTrap()
                            val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + trap.z.coerceAtLeast(0f))
                            val sx = screenW * 0.5f + trap.x * roadHalfWidth * p
                            val sy = horizonY + (playerY - horizonY) * p
                            spawnBeerSplash(sx, sy, 8, isFoam = false)

                            if (crowd.count <= 0) {
                                triggerLose()
                                return
                            }
                        }
                    }
                }

                // Подход к боссу
                boss.z = currentLevel.length - distanceTraveled
                if (boss.z <= 22f) {
                    boss.z = 22f
                    state = GameState.BOSS_FIGHT
                    crowd.targetX = 0f
                    mugLaunchTimer = 0f
                    // Длительность поедания ~2.5 секунды вне зависимости от размера толпы
                    mugLaunchInterval = (2.4f / crowd.count.coerceAtLeast(1)).coerceIn(0.012f, 0.08f)
                }
            }

            GameState.BOSS_FIGHT -> {
                crowd.targetX = 0f
                crowd.update(dt, isRunning = true)
                updateFlyingMugs(dt)

                mugLaunchTimer += dt
                while (mugLaunchTimer >= mugLaunchInterval && crowd.count > 0 && boss.currentThirst > 0) {
                    mugLaunchTimer -= mugLaunchInterval
                    // Если кружек сотни, запускаем пачками по несколько штук за раз
                    val batch = when {
                        crowd.count > 250 -> 4
                        crowd.count > 100 -> 2
                        else -> 1
                    }.coerceAtMost(crowd.count).coerceAtMost(boss.currentThirst)

                    crowd.setCount(crowd.count - batch)
                    launchFlyingMug(batch)
                }

                // Проверяем завершение боя, когда все запущенные кружки долетели
                if (!hasActiveFlyingMugs()) {
                    if (boss.currentThirst <= 0) {
                        triggerWin()
                    } else if (crowd.count <= 0) {
                        triggerLose()
                    }
                }
            }

            GameState.WIN, GameState.LOSE -> {
                crowd.update(dt, isRunning = false)
            }
        }
    }

    private fun launchFlyingMug(worthAmount: Int) {
        for (i in 0 until maxFlyingMugs) {
            if (!fActive[i]) {
                fActive[i] = true
                val spread = (Random.nextFloat() - 0.5f) * roadHalfWidth * 0.65f
                fStartX[i] = screenW * 0.5f + crowd.centerX * roadHalfWidth + spread
                fStartY[i] = playerY - 25f
                fProgress[i] = 0f
                // Мгновенно засчитываем уменьшение жажды при подлёте или по таймеру
                // Чтобы синхронизировать с визуальным попаданием в рот, сохраним вес в fProgress отрицательным смещением не нужно —
                // применим глоток при достижении progress >= 1f:
                fWorth[i] = worthAmount
                return
            }
        }
        // Если пул заполнен, применяем глоток напрямую
        boss.onDrinkMug(worthAmount)
        sound.playGulp()
    }

    private val fWorth = IntArray(maxFlyingMugs)

    private fun updateFlyingMugs(dt: Float) {
        val mouthX = screenW * 0.5f
        val mouthY = boss.getMouthScreenY(horizonY, playerY, roadHalfWidth)
        for (i in 0 until maxFlyingMugs) {
            if (fActive[i]) {
                fProgress[i] += dt * 4.2f
                if (fProgress[i] >= 1f) {
                    fActive[i] = false
                    boss.onDrinkMug(fWorth[i])
                    sound.playGulp()
                    spawnBeerSplash(mouthX, mouthY, 7, isFoam = true)
                }
            }
        }
    }

    private fun hasActiveFlyingMugs(): Boolean {
        for (i in 0 until maxFlyingMugs) {
            if (fActive[i]) return true
        }
        return false
    }

    private fun triggerWin() {
        if (state == GameState.WIN) return
        state = GameState.WIN
        crowd.setCount(0)
        boss.triggerWinBurp()
        sound.playBurp()
        sound.playWin()

        earnedCoinsThisRun = Config.BASE_WIN_COINS + (currentLevel.levelNumber - 1) * Config.COINS_PER_LEVEL
        prefs.coins += earnedCoinsThisRun
        prefs.level = currentLevel.levelNumber + 1
        spawnConfetti(90)
    }

    private fun triggerLose() {
        if (state == GameState.LOSE) return
        state = GameState.LOSE
        boss.triggerLoseRoar()
        sound.playLose()
    }

    private fun spawnBeerSplash(x: Float, y: Float, amount: Int, isFoam: Boolean) {
        var spawned = 0
        for (i in 0 until maxParticles) {
            if (!pActive[i]) {
                pActive[i] = true
                pIsConfetti[i] = false
                pX[i] = x
                pY[i] = y
                val angle = Random.nextFloat() * 2f * PI.toFloat()
                val speed = 120f + Random.nextFloat() * 340f
                pVx[i] = cos(angle) * speed
                pVy[i] = sin(angle) * speed - 180f
                pSize[i] = 6f + Random.nextFloat() * 10f
                pMaxLife[i] = 0.35f + Random.nextFloat() * 0.25f
                pLife[i] = pMaxLife[i]
                pColor[i] = if (isFoam && Random.nextBoolean()) Config.COLOR_FOAM else Config.COLOR_BEER_GOLD
                spawned++
                if (spawned >= amount) break
            }
        }
    }

    private fun spawnConfetti(amount: Int) {
        var spawned = 0
        val colors = intArrayOf(
            Color.WHITE,
            Config.COLOR_BEER_GOLD,
            Color.parseColor("#FFF9C4"),
            Color.parseColor("#4FC3F7"),
            Color.parseColor("#FF8A65")
        )
        for (i in 0 until maxParticles) {
            if (!pActive[i]) {
                pActive[i] = true
                pIsConfetti[i] = true
                pX[i] = Random.nextFloat() * screenW
                pY[i] = -Random.nextFloat() * screenH * 0.35f
                pVx[i] = (Random.nextFloat() - 0.5f) * 140f
                pVy[i] = 180f + Random.nextFloat() * 280f
                pSize[i] = 10f + Random.nextFloat() * 10f
                pMaxLife[i] = 3.2f
                pLife[i] = pMaxLife[i]
                pColor[i] = colors[Random.nextInt(colors.size)]
                spawned++
                if (spawned >= amount) break
            }
        }
    }

    private fun updateParticles(dt: Float) {
        for (i in 0 until maxParticles) {
            if (!pActive[i]) continue
            pLife[i] -= dt
            if (pLife[i] <= 0f) {
                pActive[i] = false
                continue
            }
            pX[i] += pVx[i] * dt
            pY[i] += pVy[i] * dt
            if (!pIsConfetti[i]) {
                pVy[i] += 980f * dt // Гравитация для брызг
            } else {
                pVx[i] += sin(pLife[i] * 8f) * 180f * dt
            }
        }
    }

    private fun render() {
        val canvas = holder.lockCanvas() ?: return
        try {
            drawEnvironment(canvas)
            drawWorldEntities(canvas)
            drawFlyingMugs(canvas)
            drawParticles(canvas)
            drawTopHud(canvas)

            when (state) {
                GameState.MENU -> drawMenuOverlay(canvas)
                GameState.WIN -> drawEndOverlay(canvas, isWin = true)
                GameState.LOSE -> drawEndOverlay(canvas, isWin = false)
                else -> {}
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawEnvironment(canvas: Canvas) {
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val cx = w * 0.5f

        // 1. Небо
        canvas.drawRect(0f, 0f, w, horizonY, skyPaint)

        // Декоративные облака
        drawCloud(canvas, w * 0.20f, horizonY * 0.42f, w * 0.08f)
        drawCloud(canvas, w * 0.78f, horizonY * 0.34f, w * 0.09f)

        // 2. Зелёная лужайка вокруг дороги
        grassPaint.color = Config.COLOR_GRASS_LIGHT
        canvas.drawRect(0f, horizonY, w, h, grassPaint)

        // Поперечные полосы на траве для ощущения скорости
        grassPaint.color = Config.COLOR_GRASS_DARK
        val stripePhase = (distanceTraveled % 20f) / 20f
        for (i in 0..6) {
            val zFar = (i + 1f - stripePhase) * 15f
            val zNear = zFar - 7.5f
            if (zNear > Config.Z_MAX) continue
            val p1 = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + zFar.coerceAtLeast(0f))
            val p2 = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + zNear.coerceAtLeast(0f))
            val y1 = horizonY + (playerY - horizonY) * p1
            val y2 = horizonY + (playerY - horizonY) * p2
            canvas.drawRect(0f, y1, w, y2, grassPaint)
        }

        // 3. Перспективная дорожка (трапеция от горизонта до нижнего края)
        val pFar = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + Config.Z_MAX)
        val pBottom = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH - 8f)
        val bottomY = h
        val halfWFar = roadHalfWidth * pFar
        val halfWBottom = roadHalfWidth * pBottom

        // Бордюры (золото/белый)
        val curbExtra = 1.08f
        roadPath.reset()
        roadPath.moveTo(cx - halfWFar * curbExtra, horizonY)
        roadPath.lineTo(cx + halfWFar * curbExtra, horizonY)
        roadPath.lineTo(cx + halfWBottom * curbExtra, bottomY)
        roadPath.lineTo(cx - halfWBottom * curbExtra, bottomY)
        roadPath.close()
        curbPaint.color = Config.COLOR_CURB_GOLD
        canvas.drawPath(roadPath, curbPaint)

        // Основное полотно дорожки
        roadPath.reset()
        roadPath.moveTo(cx - halfWFar, horizonY)
        roadPath.lineTo(cx + halfWFar, horizonY)
        roadPath.lineTo(cx + halfWBottom, bottomY)
        roadPath.lineTo(cx - halfWBottom, bottomY)
        roadPath.close()
        roadPaint.color = Config.COLOR_ROAD_BASE
        canvas.drawPath(roadPath, roadPaint)

        // Разделительная центральная линия и доски, бегущие навстречу
        plankPaint.color = Config.COLOR_ROAD_PLANK
        val plankStep = 10f
        val offset = distanceTraveled % plankStep
        var z = Config.Z_MAX - offset
        while (z > -6f) {
            val p = Config.FOCAL_LENGTH / (Config.FOCAL_LENGTH + z)
            val y = horizonY + (playerY - horizonY) * p
            val hw = roadHalfWidth * p
            plankPaint.strokeWidth = (3.5f * p).coerceAtLeast(1f)
            canvas.drawLine(cx - hw, y, cx + hw, y, plankPaint)
            z -= plankStep
        }

        // Центральная пунктирная разметка для удобства прицеливания в левые/правые ворота
        curbPaint.color = Color.argb(110, 255, 248, 225)
        curbPaint.strokeWidth = 5f
        canvas.drawLine(cx, horizonY, cx, bottomY, curbPaint)
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, cloudPaint)
        canvas.drawCircle(x - r * 0.75f, y + r * 0.15f, r * 0.72f, cloudPaint)
        canvas.drawCircle(x + r * 0.75f, y + r * 0.15f, r * 0.72f, cloudPaint)
    }

    private fun drawWorldEntities(canvas: Canvas) {
        val cx = screenW * 0.5f

        // 1. Босс в конце дорожки
        boss.draw(canvas, cx, horizonY, playerY, roadHalfWidth)

        // 2. Ловушки (шипы и лужи)
        for (trap in currentLevel.traps) {
            trap.draw(canvas, cx, horizonY, playerY, roadHalfWidth, worldPaints)
        }

        // 3. Ворота-гейты (рисуем от дальних к ближним)
        for (i in currentLevel.gates.indices.reversed()) {
            currentLevel.gates[i].draw(canvas, cx, horizonY, playerY, roadHalfWidth, worldPaints)
        }

        // 4. Толпа пивных кружек
        crowd.draw(canvas, cx, horizonY, playerY, roadHalfWidth)
    }

    private fun drawFlyingMugs(canvas: Canvas) {
        val mouthX = screenW * 0.5f
        val mouthY = boss.getMouthScreenY(horizonY, playerY, roadHalfWidth)
        val unitSize = roadHalfWidth * 0.085f

        for (i in 0 until maxFlyingMugs) {
            if (!fActive[i]) continue
            val t = fProgress[i]
            val sx = fStartX[i] + (mouthX - fStartX[i]) * t
            val arcLift = sin(t * PI).toFloat() * screenH * 0.12f
            val sy = fStartY[i] + (mouthY - fStartY[i]) * t - arcLift
            crowd.drawSingleUnit(canvas, sx, sy, sy + 6f, unitSize * (1f - 0.35f * t), crowd.activeSkin)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (i in 0 until maxParticles) {
            if (!pActive[i]) continue
            val alpha = ((pLife[i] / pMaxLife[i]) * 255f).toInt().coerceIn(0, 255)
            particlePaint.color = pColor[i]
            particlePaint.alpha = alpha
            if (pIsConfetti[i]) {
                tmpRect.set(pX[i] - pSize[i] * 0.6f, pY[i] - pSize[i] * 0.35f, pX[i] + pSize[i] * 0.6f, pY[i] + pSize[i] * 0.35f)
                canvas.drawRect(tmpRect, particlePaint)
            } else {
                canvas.drawCircle(pX[i], pY[i], pSize[i], particlePaint)
            }
        }
    }

    private fun drawTopHud(canvas: Canvas) {
        val w = screenW.toFloat()
        val topPad = screenH * 0.045f

        // Номер уровня
        val titleSize = (w * 0.052f).coerceIn(24f, 54f)
        drawOutlinedText(canvas, "УРОВЕНЬ ${currentLevel.levelNumber}", w * 0.5f, topPad + titleSize, titleSize)

        // Полоска прогресса уровня сверху
        val barW = w * 0.46f
        val barH = (screenH * 0.022f).coerceIn(14f, 30f)
        val barLeft = w * 0.5f - barW * 0.5f
        val barTop = topPad + titleSize + 14f

        cardPaint.color = Color.argb(140, 0, 0, 0)
        tmpRect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        canvas.drawRoundRect(tmpRect, barH * 0.5f, barH * 0.5f, cardPaint)

        val progress = (distanceTraveled / (currentLevel.length - 22f)).coerceIn(0f, 1f)
        if (progress > 0f) {
            cardPaint.color = Config.COLOR_BEER_GOLD
            tmpRect.set(barLeft, barTop, barLeft + barW * progress, barTop + barH)
            canvas.drawRoundRect(tmpRect, barH * 0.5f, barH * 0.5f, cardPaint)
        }

        cardBorderPaint.color = Color.WHITE
        cardBorderPaint.strokeWidth = 3f
        tmpRect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        canvas.drawRoundRect(tmpRect, barH * 0.5f, barH * 0.5f, cardBorderPaint)

        // Счётчик монет справа сверху
        val coinText = "🪙 ${prefs.coins}"
        val coinSize = (w * 0.044f).coerceIn(22f, 44f)
        drawOutlinedText(canvas, coinText, w * 0.85f, topPad + coinSize, coinSize)
    }

    private fun drawMenuOverlay(canvas: Canvas) {
        val w = screenW.toFloat()
        val h = screenH.toFloat()

        // Логотип игры
        val logoSize = (w * 0.13f).coerceIn(48f, 120f)
        drawOutlinedText(canvas, "BEER RUN", w * 0.5f, h * 0.31f, logoSize, fill = Config.COLOR_BEER_GOLD)

        // Пульсирующая подсказка старта
        val pulse = 1f + sin(System.currentTimeMillis() * 0.006f).toFloat() * 0.06f
        val hintSize = (w * 0.054f * pulse).coerceIn(24f, 52f)
        drawOutlinedText(canvas, "ПРОВЕДИ, ЧТОБЫ БЕЖАТЬ!", w * 0.5f, h * 0.43f, hintSize)

        // Заголовок магазина скинов
        val shopTitleSize = (w * 0.042f).coerceIn(20f, 40f)
        drawOutlinedText(canvas, "МАГАЗИН СКИНОВ", w * 0.5f, shopCardRects[0].top - 18f, shopTitleSize)

        // 3 карточки скинов
        val skins = Config.Skin.values()
        for (i in skins.indices) {
            val skin = skins[i]
            val rect = shopCardRects[i]
            val unlocked = prefs.isSkinUnlocked(skin)
            val selected = crowd.activeSkin == skin

            cardPaint.color = when {
                selected -> Color.parseColor("#FFB300")
                unlocked -> Color.argb(220, 38, 50, 56)
                else -> Color.argb(200, 20, 26, 36)
            }
            canvas.drawRoundRect(rect, 22f, 22f, cardPaint)

            cardBorderPaint.color = if (selected) Color.WHITE else Color.argb(160, 255, 255, 255)
            cardBorderPaint.strokeWidth = if (selected) 6f else 3f
            canvas.drawRoundRect(rect, 22f, 22f, cardBorderPaint)

            // Превью скина внутри карточки
            val previewX = rect.centerX()
            val previewY = rect.top + rect.height() * 0.56f
            val previewSize = rect.width() * 0.22f
            crowd.drawSingleUnit(canvas, previewX, previewY, previewY + 4f, previewSize, skin)

            // Название и цена/статус
            val labelSize = (rect.width() * 0.13f).coerceIn(16f, 32f)
            drawOutlinedText(canvas, skin.title, rect.centerX(), rect.top + rect.height() * 0.18f, labelSize * 0.85f)

            val statusText = when {
                selected -> "ВЫБРАНО"
                unlocked -> "ВЗЯТЬ"
                else -> "🪙 ${skin.price}"
            }
            val statusColor = if (!unlocked && prefs.coins < skin.price) {
                Color.parseColor("#FF8A80")
            } else {
                Color.WHITE
            }
            drawOutlinedText(canvas, statusText, rect.centerX(), rect.bottom - 16f, labelSize * 0.9f, fill = statusColor)
        }
    }

    private fun drawEndOverlay(canvas: Canvas, isWin: Boolean) {
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        canvas.drawRect(0f, 0f, w, h, overlayBgPaint)

        val bigSize = (w * 0.14f).coerceIn(52f, 130f)
        if (isWin) {
            drawOutlinedText(canvas, "WIN!", w * 0.5f, h * 0.36f, bigSize, fill = Config.COLOR_BEER_GOLD)
            val subSize = (w * 0.056f).coerceIn(24f, 54f)
            drawOutlinedText(canvas, "БОСС НАПИЛСЯ!", w * 0.5f, h * 0.44f, subSize)
            drawOutlinedText(canvas, "+$earnedCoinsThisRun 🪙", w * 0.5f, h * 0.52f, subSize * 1.15f, fill = Color.parseColor("#FFF59D"))
        } else {
            drawOutlinedText(canvas, "LOSE", w * 0.5f, h * 0.36f, bigSize, fill = Config.COLOR_GATE_BAD)
            val subSize = (w * 0.052f).coerceIn(22f, 50f)
            drawOutlinedText(canvas, "КРУЖКИ КОНЧИЛИСЬ!", w * 0.5f, h * 0.45f, subSize)
        }

        // Кнопка действия ("ДАЛЬШЕ" или "ЗАНОВО")
        cardPaint.color = if (isWin) Color.parseColor("#00C853") else Color.parseColor("#FF6D00")
        canvas.drawRoundRect(actionButtonRect, 34f, 34f, cardPaint)

        cardBorderPaint.color = Color.WHITE
        cardBorderPaint.strokeWidth = 6f
        canvas.drawRoundRect(actionButtonRect, 34f, 34f, cardBorderPaint)

        val btnText = if (isWin) "ДАЛЬШЕ" else "ЗАНОВО"
        val btnTextSize = (actionButtonRect.height() * 0.46f).coerceIn(24f, 54f)
        drawOutlinedText(
            canvas,
            btnText,
            actionButtonRect.centerX(),
            actionButtonRect.centerY() + btnTextSize * 0.35f,
            btnTextSize
        )
    }

    private fun drawOutlinedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        fill: Int = Color.WHITE
    ) {
        hudStrokePaint.textSize = size
        hudStrokePaint.strokeWidth = (size * 0.16f).coerceAtLeast(3f)
        hudFillPaint.textSize = size
        hudFillPaint.color = fill
        canvas.drawText(text, x, y, hudStrokePaint)
        canvas.drawText(text, x, y, hudFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                isDragging = true

                if (state == GameState.MENU) {
                    // Проверяем нажатие на карточки магазина
                    val skins = Config.Skin.values()
                    for (i in skins.indices) {
                        if (shopCardRects[i].contains(x, y)) {
                            handleSkinClick(skins[i])
                            isDragging = false
                            return true
                        }
                    }
                    // Нажатие выше магазина запускает забег
                    state = GameState.PLAYING
                    sound.playClick()
                } else if (state == GameState.WIN || state == GameState.LOSE) {
                    if (actionButtonRect.contains(x, y)) {
                        sound.playClick()
                        loadLevel(prefs.level, toMenu = false)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && (state == GameState.PLAYING || state == GameState.MENU)) {
                    val dx = x - lastTouchX
                    lastTouchX = x
                    if (state == GameState.MENU && abs(dx) > 6f) {
                        state = GameState.PLAYING
                    }
                    if (roadHalfWidth > 0f) {
                        crowd.targetX += (dx / roadHalfWidth) * Config.STEER_SENSITIVITY
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    private fun handleSkinClick(skin: Config.Skin) {
        if (prefs.isSkinUnlocked(skin)) {
            prefs.selectedSkinId = skin.id
            crowd.activeSkin = skin
            sound.playClick()
        } else if (prefs.coins >= skin.price) {
            prefs.coins -= skin.price
            prefs.unlockSkin(skin)
            prefs.selectedSkinId = skin.id
            crowd.activeSkin = skin
            sound.playGateGood()
            spawnBeerSplash(screenW * 0.5f, playerY - 40f, 20, isFoam = true)
        } else {
            sound.playGateBad()
        }
    }
}
