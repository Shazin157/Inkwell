package com.lumina.reader.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.lumina.reader.data.storage.BookRepository
import com.lumina.reader.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read user theme preference (light/dark/system) before inflating
        val repository = BookRepository(this)
        val settings = repository.loadDisplaySettings()
        when (settings.appThemeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startBootUpAnimation()
    }

    private fun startBootUpAnimation() {
        // Initial animation states
        binding.cardSplashLogo.alpha = 0f
        binding.cardSplashLogo.scaleX = 0.7f
        binding.cardSplashLogo.scaleY = 0.7f

        binding.tvSplashTitle.alpha = 0f
        binding.tvSplashTitle.translationY = 24f

        binding.tvSplashSubtitle.alpha = 0f
        binding.tvSplashSubtitle.translationY = 16f

        binding.pbSplashLoading.alpha = 0f

        // 1. Logo scale & pop in
        binding.cardSplashLogo.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(700)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 2. Title slide & fade
        binding.tvSplashTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(280)
            .setDuration(550)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 3. Subtitle slide & fade
        binding.tvSplashSubtitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(420)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 4. Progress bar fade in
        binding.pbSplashLoading.animate()
            .alpha(0.85f)
            .setStartDelay(550)
            .setDuration(400)
            .start()

        // 5. Seamless launch into MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1300)
    }
}
