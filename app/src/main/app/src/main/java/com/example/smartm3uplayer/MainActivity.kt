package com.example.smartm3uplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private val hideUiHandler = Handler(Looper.getMainLooper())
    private val autoHideDelayMs = 4000L

    private val hideUiRunnable = Runnable {
        enterImmersiveFullScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enterImmersiveFullScreen()

        findViewById<View>(R.id.rootContainer)?.setOnClickListener {
            toggleControlsVisibility()
        }

        findViewById<Button>(R.id.btnSelectChannel)?.setOnClickListener {
            onChannelSelected()
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideUiHandler.removeCallbacks(hideUiRunnable)
    }

    fun onChannelSelected() {
        showControlsTemporarily(autoHideDelayMs)
    }

    fun showControlsTemporarily(delayMillis: Long = autoHideDelayMs) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())

        findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.bottomControls)?.visibility = View.VISIBLE

        hideUiHandler.removeCallbacks(hideUiRunnable)
        hideUiHandler.postDelayed(hideUiRunnable, delayMillis)
    }

    fun enterImmersiveFullScreen() {
        hideUiHandler.removeCallbacks(hideUiRunnable)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        findViewById<View>(R.id.topBar)?.visibility = View.GONE
        findViewById<View>(R.id.bottomControls)?.visibility = View.GONE
    }

    private fun toggleControlsVisibility() {
        val topBar = findViewById<View>(R.id.topBar)
        val isVisible = topBar?.visibility == View.VISIBLE
        if (isVisible) {
            enterImmersiveFullScreen()
        } else {
            showControlsTemporarily(autoHideDelayMs)
        }
    }
}
