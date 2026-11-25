package com.osama.escapehouse

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import com.osama.escapehouse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), GameListener {

    private lateinit var binding: ActivityMainBinding
    private var difficulty = 1
    private var pausedByButton = false
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("escape_house", MODE_PRIVATE)

        binding.gameView.listener = this
        updateBestLabel()

        binding.btnEasy.setOnClickListener { difficulty = 0; Toast.makeText(this, "الصعوبة: سهل", Toast.LENGTH_SHORT).show() }
        binding.btnNormal.setOnClickListener { difficulty = 1; Toast.makeText(this, "الصعوبة: عادي", Toast.LENGTH_SHORT).show() }
        binding.btnHard.setOnClickListener { difficulty = 2; Toast.makeText(this, "الصعوبة: صعب", Toast.LENGTH_SHORT).show() }
        binding.btnHow.setOnClickListener {
            binding.tvHow.visibility = if (binding.tvHow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnStart.setOnClickListener { startGame() }
        binding.btnHide.setOnClickListener { binding.gameView.tryHide() }
        binding.btnPause.setOnClickListener {
            if (pausedByButton) {
                binding.gameView.resumeGame()
                binding.btnPause.setText(R.string.pause)
                pausedByButton = false
            } else {
                binding.gameView.pauseGame()
                binding.btnPause.setText(R.string.resume)
                pausedByButton = true
            }
        }
        binding.btnRestart.setOnClickListener {
            binding.menuEnd.visibility = View.GONE
            startGame()
        }
        binding.btnMenu.setOnClickListener {
            binding.menuEnd.visibility = View.GONE
            binding.hud.visibility = View.GONE
            binding.gameButtons.visibility = View.GONE
            binding.gameView.stopGame()
            binding.menuStart.visibility = View.VISIBLE
            updateBestLabel()
        }
    }

    private fun startGame() {
        binding.menuStart.visibility = View.GONE
        binding.menuEnd.visibility = View.GONE
        binding.hud.visibility = View.VISIBLE
        binding.gameButtons.visibility = View.VISIBLE
        binding.btnPause.setText(R.string.pause)
        pausedByButton = false
        binding.gameView.startGame(difficulty)
    }

    private fun updateBestLabel() {
        val best = prefs.getLong("best_time", -1)
        binding.tvBest.text = if (best > 0) "${getString(R.string.best)}: ${formatTime(best)}" else ""
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onHud(day: Int, items: Int, seconds: Long) {
        runOnUiThread {
            binding.tvDay.text = "${getString(R.string.day)} $day / 5"
            binding.tvItems.text = "${getString(R.string.items)}: $items / 4"
            binding.tvTime.text = formatTime(seconds)
        }
    }

    override fun onCaught(day: Int) {
        runOnUiThread {
            binding.tvMessage.text = "${getString(R.string.caught)} — ${getString(R.string.day)} $day"
            binding.tvMessage.visibility = View.VISIBLE
            binding.tvMessage.postDelayed({ binding.tvMessage.visibility = View.GONE }, 1800)
        }
    }

    override fun onWin(seconds: Long) {
        runOnUiThread {
            val best = prefs.getLong("best_time", -1)
            if (best < 0 || seconds < best) prefs.edit().putLong("best_time", seconds).apply()
            binding.hud.visibility = View.GONE
            binding.gameButtons.visibility = View.GONE
            binding.tvEndTitle.text = getString(R.string.win)
            binding.tvEndText.text = getString(R.string.win_text)
            binding.tvEndTime.text = "${getString(R.string.time)}: ${formatTime(seconds)}"
            binding.menuEnd.visibility = View.VISIBLE
        }
    }

    override fun onGameOver() {
        runOnUiThread {
            binding.hud.visibility = View.GONE
            binding.gameButtons.visibility = View.GONE
            binding.tvEndTitle.text = getString(R.string.lose)
            binding.tvEndText.text = getString(R.string.lose_text)
            binding.tvEndTime.text = ""
            binding.menuEnd.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.menuStart.visibility != View.VISIBLE && binding.menuEnd.visibility != View.VISIBLE) {
            binding.gameView.pauseGame()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pausedByButton && binding.menuStart.visibility != View.VISIBLE && binding.menuEnd.visibility != View.VISIBLE) {
            binding.gameView.resumeGame()
        }
    }
}
