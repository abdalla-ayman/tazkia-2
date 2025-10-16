package com.tazkia.ai.blurfilter.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.utils.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefManager: PreferenceManager

    // UI Elements
    private lateinit var switchGpu: SwitchCompat
    private lateinit var switchAdaptiveFps: SwitchCompat
    private lateinit var radioGroupResolution: RadioGroup
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefManager = PreferenceManager(this)
        LanguageHelper.setLanguage(this, prefManager.language)

        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        initializeViews()
        loadSettings()
        setupListeners()
    }

    private fun initializeViews() {
        switchGpu = findViewById(R.id.switchGpu)
        switchAdaptiveFps = findViewById(R.id.switchAdaptiveFps)
        radioGroupResolution = findViewById(R.id.radioGroupResolution)
        tvVersion = findViewById(R.id.tvVersion)
    }

    private fun loadSettings() {
        // GPU setting
        switchGpu.isChecked = prefManager.useGpu

        // Adaptive FPS
        switchAdaptiveFps.isChecked = prefManager.adaptiveFps

        // Resolution
        when (prefManager.processingResolution) {
            PreferenceManager.RESOLUTION_LOW -> findViewById<View>(R.id.radioResLow).performClick()
            PreferenceManager.RESOLUTION_MEDIUM -> findViewById<View>(R.id.radioResMedium).performClick()
            PreferenceManager.RESOLUTION_HIGH -> findViewById<View>(R.id.radioResHigh).performClick()
        }

        // Version - using hardcoded version since BuildConfig might not be available yet
        tvVersion.text = "${getString(R.string.version)} 1.0"
    }

    private fun setupListeners() {
        // GPU switch
        switchGpu.setOnCheckedChangeListener { _, isChecked ->
            prefManager.useGpu = isChecked
        }

        // Adaptive FPS switch
        switchAdaptiveFps.setOnCheckedChangeListener { _, isChecked ->
            prefManager.adaptiveFps = isChecked
        }

        // Resolution radio group
        radioGroupResolution.setOnCheckedChangeListener { _, checkedId ->
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