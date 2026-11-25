package com.osama.escapehouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

interface GameListener {
    fun onHud(day: Int, items: Int, seconds: Long)
    fun onCaught(day: Int)
    fun onWin(seconds: Long)
    fun onGameOver()
}

data class GameItem(val tx: Int, val ty: Int, val emoji: String, val name: String, var collected: Boolean = false)

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    var listener: GameListener? = null
    var isPaused = false
        private set
    var isRunning = false
        private set

    private var thread: Thread? = null
    private var lastTime = 0L

    companion object {
        const val COLS = 18
        const val ROWS = 12
    }

    private val walls = Array(ROWS) { BooleanArray(COLS) }
    private var tile = 60f
    private var offX = 0f
    private var offY = 0f

    private var px = 0f
    private var py = 0f
    private var gx = 0f
    private var gy = 0f
    private var day = 1
    private var startMillis = 0L
    private var elapsedSec = 0L
    private var difficulty = 1
    private var grannySpeed = 135f
    private var hiddenUntil = 0L
    private var chaseUntil = 0L
    private var searchX = 0f
    private var searchY = 0f
    private var hasSearchTarget = false
    private var patrolIndex = 0
    private val patrol = listOf(Pair(9, 2), Pair(15, 5), Pair(9, 9), Pair(3, 5), Pair(15, 2), Pair(3, 9))

    private val items = mutableListOf<GameItem>()
    private val hidingSpots = listOf(Pair(2, 4), Pair(16, 7), Pair(10, 3))
    private val exitTx = 16
    private val exitTy = 1

    // joystick
    private var joyActive = false
    private var joyBaseX = 0f
    private var joyBaseY = 0f
    private var joyDx = 0f
    private var joyDy = 0f
    private var joyMag = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BBBBBB"); textSize = 22f; textAlign = Paint.Align.CENTER }

    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    init {
        holder.addCallback(this)
        buildWalls()
        resetPositions()
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: Exception) { tone = null }
        try { vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } catch (e: Exception) { vibrator = null }
    }

    private fun buildWalls() {
        for (y in 0 until ROWS) for (x in 0 until COLS) walls[y][x] = (x == 0 || y == 0 || x == COLS - 1 || y == ROWS - 1)
        for (y in 1..10) {
            if (y != 3 && y != 8) walls[y][6] = true
            if (y != 2 && y != 9) walls[y][12] = true
        }
        for (x in 1..5) if (x != 3) walls[6][x] = true
        for (x in 7..11) if (x != 9) walls[6][x] = true
        for (x in 13..16) if (x != 15) walls[6][x] = true
        // exit tile itself is floor
        walls[exitTy][exitTx] = false
    }

    private fun tileCenterX(tx: Int) = offX + (tx + 0.5f) * tile
    private fun tileCenterY(ty: Int) = offY + (ty + 0.5f) * tile
    private fun isWall(tx: Int, ty: Int): Boolean {
        if (tx < 0 || ty < 0 || tx >= COLS || ty >= ROWS) return true
        return walls[ty][tx]
    }

    private fun resetPositions() {
        items.clear()
        items.add(GameItem(15, 2, "🔑", "مفتاح الباب"))
        items.add(GameItem(3, 2, "🔨", "المطرقة"))
        items.add(GameItem(15, 9, "🔋", "البطارية"))
        items.add(GameItem(3, 9, "📄", "ورقة الرمز"))
        px = tileCenterX(2); py = tileCenterY(9)
        gx = tileCenterX(9); gy = tileCenterY(5)
        patrolIndex = 0
        hiddenUntil = 0L
        hasSearchTarget = false
    }

    fun startGame(diff: Int) {
        difficulty = diff
        grannySpeed = when (diff) { 0 -> 105f; 2 -> 168f; else -> 135f }
        day = 1
        elapsedSec = 0L
        startMillis = System.currentTimeMillis()
        resetPositions()
        isPaused = false
        isRunning = true
        startThread()
    }

    fun pauseGame() { isPaused = true }
    fun resumeGame() { isPaused = false; lastTime = System.currentTimeMillis() }
    fun stopGame() { isRunning = false; isPaused = false }

    fun tryHide() {
        if (!isRunning || isPaused) return
        val now = System.currentTimeMillis()
        for ((tx, ty) in hidingSpots) {
            val cx = tileCenterX(tx); val cy = tileCenterY(ty)
            if (hypot(px - cx, py - cy) < tile * 1.2f) {
                hiddenUntil = now + 6000
                hasSearchTarget = false
                try { tone?.startTone(ToneGenerator.TONE_CDMA_PIP, 120) } catch (e: Exception) {}
                return
            }
        }
    }

    private fun startThread() {
        if (thread?.isAlive == true) return
        lastTime = System.currentTimeMillis()
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { if (isRunning) startThread() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        tile = min(width / COLS.toFloat(), height / ROWS.toFloat())
        offX = (width - tile * COLS) / 2f
        offY = (height - tile * ROWS) / 2f
        // keep positions in sync with new tile size
        px = tileCenterX(2); py = tileCenterY(9)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        try { thread?.join(500) } catch (e: Exception) {}
        thread = null
    }

    override fun run() {
        while (isRunning) {
            val now = System.currentTimeMillis()
            val dt = ((now - lastTime) / 1000f).coerceIn(0f, 0.05f)
            lastTime = now
            if (!isPaused) {
                update(dt, now)
                drawGame()
            }
            try { Thread.sleep(16) } catch (e: InterruptedException) { break }
        }
    }

    private fun update(dt: Float, now: Long) {
        elapsedSec = (now - startMillis) / 1000
        val hidden = now < hiddenUntil

        // player movement
        if (joyActive && !hidden) {
            val running = joyMag > 0.85f
            val speed = if (running) 265f else 175f
            val nx = px + joyDx * speed * dt
            val ny = py + joyDy * speed * dt
            movePlayer(nx, ny)
            if (running) {
                // noise attracts grandmother
                if (hypot(gx - px, gy - py) < tile * 5f) {
                    searchX = px; searchY = py; hasSearchTarget = true; chaseUntil = now + 2500
                }
            }
        }

        // pick up items
        for (item in items) {
            if (!item.collected && hypot(px - tileCenterX(item.tx), py - tileCenterY(item.ty)) < tile * 0.7f) {
                item.collected = true
                try { tone?.startTone(ToneGenerator.TONE_CDMA_PIP, 150) } catch (e: Exception) {}
                try { vibrator?.vibrate(60) } catch (e: Exception) {}
            }
        }

        // win check
        val collected = items.count { it.collected }
        if (collected == items.size) {
            if (hypot(px - tileCenterX(exitTx), py - tileCenterY(exitTy)) < tile * 0.7f) {
                isRunning = false
                listener?.onWin(elapsedSec)
                return
            }
        }

        // grandmother AI
        val dist = hypot(gx - px, gy - py)
        val seesPlayer = !hidden && dist < tile * 3.2f
        val chasing = seesPlayer || (hasSearchTarget && now < chaseUntil)
        var tx: Float
        var ty: Float
        var speed = grannySpeed
        if (seesPlayer) {
            tx = px; ty = py; speed = grannySpeed * 1.15f
            searchX = px; searchY = py; hasSearchTarget = true; chaseUntil = now + 2000
        } else if (hasSearchTarget && now < chaseUntil) {
            tx = searchX; ty = searchY
        } else {
            hasSearchTarget = false
            val p = patrol[patrolIndex]
            tx = tileCenterX(p.first); ty = tileCenterY(p.second)
            if (hypot(gx - tx, gy - ty) < tile * 0.4f) patrolIndex = (patrolIndex + 1) % patrol.size
        }
        val dx = tx - gx; val dy = ty - gy; val d = hypot(dx, dy)
        if (d > 4f) {
            val nx = gx + dx / d * speed * dt
            val ny = gy + dy / d * speed * dt
            // grandmother walks through doors but not walls: simple axis move with wall check
            if (!hitsWall(nx, gy)) gx = nx
            if (!hitsWall(gx, ny)) gy = ny
        }

        // caught?
        if (!hidden && hypot(gx - px, gy - py) < tile * 0.55f) {
            try { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) } catch (e: Exception) {}
            try { vibrator?.vibrate(400) } catch (e: Exception) {}
            if (day >= 5) {
                isRunning = false
                listener?.onGameOver()
            } else {
                day++
                grannySpeed += 8f
                px = tileCenterX(2); py = tileCenterY(9)
                gx = tileCenterX(9); gy = tileCenterY(5)
                hasSearchTarget = false
                listener?.onCaught(day)
            }
            return
        }

        // heartbeat when close
        if (dist < tile * 2.5f && Random.nextFloat() < dt * 2f) {
            try { tone?.startTone(ToneGenerator.TONE_CDMA_PIP, 60) } catch (e: Exception) {}
        }

        listener?.onHud(day, collected, elapsedSec)
    }

    private fun hitsWall(x: Float, y: Float): Boolean {
        val r = tile * 0.28f
        val pts = listOf(Pair(x - r, y - r), Pair(x + r, y - r), Pair(x - r, y + r), Pair(x + r, y + r))
        for ((cx, cy) in pts) {
            val tx = ((cx - offX) / tile).toInt()
            val ty = ((cy - offY) / tile).toInt()
            if (isWall(tx, ty)) return true
        }
        return false
    }

    private fun movePlayer(nx: Float, ny: Float) {
        if (!hitsWall(nx, py)) px = nx
        if (!hitsWall(px, ny)) py = ny
    }

    private fun drawGame() {
        val canvas: Canvas? = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
        try {
            canvas.drawColor(Color.parseColor("#0D0D0D"))
            val now = System.currentTimeMillis()
            val hidden = now < hiddenUntil
            // floor + walls
            for (y in 0 until ROWS) {
                for (x in 0 until COLS) {
                    val left = offX + x * tile; val top = offY + y * tile
                    val rect = RectF(left, top, left + tile, top + tile)
                    if (walls[y][x]) {
                        paint.color = Color.parseColor("#1F1F1F"); canvas.drawRect(rect, paint)
                        paint.color = Color.parseColor("#8B0000"); canvas.drawRect(left, top, left + tile, top + 4f, paint)
                    } else {
                        paint.color = if ((x + y) % 2 == 0) Color.parseColor("#2A1F16") else Color.parseColor("#33261A")
                        canvas.drawRect(rect, paint)
                    }
                }
            }
            // room labels
            canvas.drawText("غرفة النوم", tileCenterX(3), tileCenterY(10) - tile * 0.3f, smallPaint)
            canvas.drawText("المطبخ", tileCenterX(9), tileCenterY(2), smallPaint)
            canvas.drawText("الصالة", tileCenterX(9), tileCenterY(8), smallPaint)
            // exit
            paint.color = Color.parseColor("#2E7D32")
            canvas.drawRect(RectF(offX + exitTx * tile + 4, offY + exitTy * tile + 4, offX + (exitTx + 1) * tile - 4, offY + (exitTy + 1) * tile - 4), paint)
            canvas.drawText("🚪", tileCenterX(exitTx), tileCenterY(exitTy) + 12f, textPaint)
            // hiding spots
            for ((tx, ty) in hidingSpots) {
                paint.color = Color.parseColor("#5D4037")
                canvas.drawRect(RectF(offX + tx * tile + 6, offY + ty * tile + 6, offX + (tx + 1) * tile - 6, offY + (ty + 1) * tile - 6), paint)
                canvas.drawText("خزانة", tileCenterX(tx), tileCenterY(ty) + 30f, smallPaint)
            }
            // items
            for (item in items) {
                if (!item.collected) canvas.drawText(item.emoji, tileCenterX(item.tx), tileCenterY(item.ty) + 12f, textPaint)
            }
            // grandmother
            paint.color = Color.parseColor("#9E9E9E")
            canvas.drawCircle(gx, gy, tile * 0.36f, paint)
            paint.color = Color.RED
            canvas.drawCircle(gx - 8f, gy - 6f, 5f, paint)
            canvas.drawCircle(gx + 8f, gy - 6f, 5f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(gx, gy + 10f, 3f, paint)
            // player (hidden = faded)
            paint.color = if (hidden) Color.parseColor("#448AFF") else Color.parseColor("#2196F3")
            paint.alpha = if (hidden) 110 else 255
            canvas.drawCircle(px, py, tile * 0.32f, paint)
            paint.alpha = 255
            paint.color = Color.WHITE
            canvas.drawCircle(px - 7f, py - 5f, 4f, paint)
            canvas.drawCircle(px + 7f, py - 5f, 4f, paint)
            // joystick
            if (joyActive) {
                paint.color = Color.parseColor("#66FFFFFF")
                canvas.drawCircle(joyBaseX, joyBaseY, 70f, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(joyBaseX + joyDx * 45f, joyBaseY + joyDy * 45f, 28f, paint)
            }
            // danger vignette
            val dist = hypot(gx - px, gy - py)
            if (dist < tile * 2.5f) {
                paint.color = Color.argb(40, 255, 0, 0)
                canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            }
            if (hidden) {
                smallPaint.color = Color.parseColor("#FFC107")
                canvas.drawText("مختبئ…", px, py - tile * 0.6f, smallPaint)
                smallPaint.color = Color.parseColor("#BBBBBB")
            }
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) {}
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isRunning || isPaused) return true
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (x < width * 0.55f) {
                    joyActive = true; joyBaseX = x; joyBaseY = y; joyDx = 0f; joyDy = 0f; joyMag = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joyActive) {
                    var dx = (x - joyBaseX) / 70f
                    var dy = (y - joyBaseY) / 70f
                    val mag = hypot(dx, dy)
                    if (mag > 1f) { dx /= mag; dy /= mag }
                    joyDx = dx; joyDy = dy; joyMag = min(mag, 1f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                joyActive = false; joyDx = 0f; joyDy = 0f; joyMag = 0f
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isRunning = false
        try { tone?.release() } catch (e: Exception) {}
    }
}
