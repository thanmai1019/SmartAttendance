package com.project.smartattendance

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity

class SplashActivity : ComponentActivity() {
    private val launchHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splashLogo)
        val title = findViewById<TextView>(R.id.splashTitle)

        logo.alpha = 0f
        logo.scaleX = 0.82f
        logo.scaleY = 0.82f
        title.alpha = 0f
        title.translationY = 28f

        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520L)
            .start()

        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(220L)
            .setDuration(450L)
            .start()

        launchHandler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1250L)
    }

    override fun onDestroy() {
        launchHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
