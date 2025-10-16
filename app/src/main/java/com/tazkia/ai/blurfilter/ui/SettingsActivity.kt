package com.tazkia.ai.blurfilter.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tazkia.ai.blurfilter.BuildConfig
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.databinding.ActivitySettingsBinding
import com.tazkia.ai.blurfilter.utils.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefManager = PreferenceManager(this)
        LanguageHelper.setLanguage(this, prefManager.language)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        // GPU setting
        binding.switchGpu.isChecked = prefManager.useGpu

        // Adaptive FPS
        binding.switchAdaptiveFps.isChecked = prefManager.adaptiveFps

        // Resolution
        when (prefManager.processingResolution) {
            PreferenceManager.RESOLUTION_LOW -> binding.radioResLow.isChecked = true
            PreferenceManager.RESOLUTION_MEDIUM -> binding.radioResMedium.isChecked = true
            PreferenceManager.RESOLUTION_HIGH -> binding.radioResHigh.isChecked = true
        }

        // Version
        binding.tvVersion.text = "${getString(R.string.version)} ${BuildConfig.VERSION_NAME}"
    }

    private fun setupListeners() {
        // GPU switch
        binding.switchGpu.setOnCheckedChangeListener { _, isChecked ->
            prefManager.useGpu = isChecked
        }

        // Adaptive FPS switch
        binding.switchAdaptiveFps.setOnCheckedChangeListener { _, isChecked ->
            prefManager.adaptiveFps = isChecked
        }

        // Resolution radio group
        binding.radioGroupResolution.setOnCheckedChangeListener { _, checkedId ->
            prefManager.processingResolution = when (checkedId) {
                R.id.radioResLow -> PreferenceManager.RESOLUTION_LOW
                R.id.radioResHigh -> PreferenceManager.RESOLUTION_HIGH
                else -> PreferenceManager.RESOLUTION_MEDIUM
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}