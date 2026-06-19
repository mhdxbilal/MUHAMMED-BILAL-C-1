package com.continental.player.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.continental.player.data.SettingsRepository

/**
 * Every screen in the app extends this so "full screen, no notification bar in the way" is
 * enforced consistently rather than re-implemented per activity. Respects the one safety-valve
 * setting (Settings → Display → Immersive mode) in case someone wants the system bars back.
 */
abstract class BaseActivity : AppCompatActivity() {

    protected val settings: SettingsRepository by lazy { SettingsRepository.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    protected fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (settings.fullscreenImmersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
