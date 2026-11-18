package com.lumina.reader.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.lumina.reader.R
import com.lumina.reader.data.model.DisplaySettings
import com.lumina.reader.data.storage.BookRepository
import com.lumina.reader.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: BookRepository
    private lateinit var settings: DisplaySettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = BookRepository(this)
        settings = repository.loadDisplaySettings()

        setupViews()
        calculateCacheSize()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // App Theme (Light / Dark / System)
        updateThemeModeButtons(settings.appThemeMode)

        binding.btnThemeSystem.setOnClickListener {
            setAppThemeMode("system")
        }
        binding.btnThemeLight.setOnClickListener {
            setAppThemeMode("light")
        }
        binding.btnThemeDark.setOnClickListener {
            setAppThemeMode("dark")
        }

        // Reader Name
        binding.tvCurrentReaderName.text = settings.readerName
        binding.btnEditReaderName.setOnClickListener {
            showEditReaderNameDialog()
        }

        // Paper Matte Grain Overlay
        binding.switchSettingsPaperMatte.isChecked = settings.einkMatteOverlay
        binding.switchSettingsPaperMatte.setOnCheckedChangeListener { _, isChecked ->
            settings.einkMatteOverlay = isChecked
            repository.saveDisplaySettings(settings)
        }

        // E-Ink Refresh Pulse Flash
        binding.switchSettingsEinkFlash.isChecked = settings.einkPageRefreshFlash
        binding.switchSettingsEinkFlash.setOnCheckedChangeListener { _, isChecked ->
            settings.einkPageRefreshFlash = isChecked
            repository.saveDisplaySettings(settings)
        }

        // Keep Screen Awake
        binding.switchSettingsKeepAwake.isChecked = settings.keepScreenAwake
        binding.switchSettingsKeepAwake.setOnCheckedChangeListener { _, isChecked ->
            settings.keepScreenAwake = isChecked
            repository.saveDisplaySettings(settings)
        }

        // Clear Unpacked Cache
        binding.btnClearCache.setOnClickListener {
            val unpackedDir = File(filesDir, "unpacked")
            if (unpackedDir.exists()) {
                unpackedDir.deleteRecursively()
            }
            Toast.makeText(this, "EPUB cache cleared successfully!", Toast.LENGTH_SHORT).show()
            calculateCacheSize()
        }
    }

    private fun setAppThemeMode(mode: String) {
        settings.appThemeMode = mode
        repository.saveDisplaySettings(settings)
        updateThemeModeButtons(mode)

        when (mode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun updateThemeModeButtons(activeMode: String) {
        val activeBg = getColor(R.color.brand_primary_container)
        val activeText = getColor(R.color.brand_on_primary_container)
        val inactiveBg = getColor(R.color.transparent)
        val inactiveText = getColor(R.color.text_secondary)

        // System
        binding.btnThemeSystem.setBackgroundColor(if (activeMode == "system") activeBg else inactiveBg)
        binding.btnThemeSystem.setTextColor(if (activeMode == "system") activeText else inactiveText)

        // Light
        binding.btnThemeLight.setBackgroundColor(if (activeMode == "light") activeBg else inactiveBg)
        binding.btnThemeLight.setTextColor(if (activeMode == "light") activeText else inactiveText)

        // Dark
        binding.btnThemeDark.setBackgroundColor(if (activeMode == "dark") activeBg else inactiveBg)
        binding.btnThemeDark.setTextColor(if (activeMode == "dark") activeText else inactiveText)
    }

    private fun showEditReaderNameDialog() {
        val input = EditText(this).apply {
            setText(settings.readerName)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Your Reader Name")
            .setMessage("This name is used for your home greeting and reading sanctuary:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    settings.readerName = newName
                    repository.saveDisplaySettings(settings)
                    binding.tvCurrentReaderName.text = newName
                    Toast.makeText(this, "Name updated!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun calculateCacheSize() {
        val unpackedDir = File(filesDir, "unpacked")
        if (unpackedDir.exists()) {
            val bytes = getFolderSize(unpackedDir)
            val mb = bytes / (1024.0 * 1024.0)
            binding.tvCacheSize.text = String.format("Current cache: %.2f MB", mb)
        } else {
            binding.tvCacheSize.text = "Current cache: 0.00 MB"
        }
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                size += getFolderSize(child)
            }
        } else {
            size = file.length()
        }
        return size
    }
}
