package com.lumina.reader.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

        // Kindle Warm Frontlight
        binding.tvSettingsWarmthPercent.text = "${settings.einkWarmthPercent}%"
        binding.sbSettingsWarmth.progress = settings.einkWarmthPercent
        binding.sbSettingsWarmth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settings.einkWarmthPercent = progress
                    binding.tvSettingsWarmthPercent.text = "$progress%"
                    repository.saveDisplaySettings(settings)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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
