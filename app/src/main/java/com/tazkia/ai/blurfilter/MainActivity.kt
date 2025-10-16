package com.tazkia.ai.blurfilter.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.service.ScreenCaptureService
import com.tazkia.ai.blurfilter.utils.PermissionHelper
import com.tazkia.ai.blurfilter.utils.PreferenceManager
import android.widget.TextView
import android.widget.Spinner
import android.widget.RadioGroup
import android.widget.Button
import android.widget.ImageButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var prefManager: PreferenceManager
    private var isRunning = false

    // UI Elements
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var btnSettings: ImageButton
    private lateinit var spinnerMode: Spinner
    private lateinit var radioGroupFilter: RadioGroup
    private lateinit var tvBlurLabel: TextView
    private lateinit var tvBlurValue: TextView
    private lateinit var seekBarBlur: SeekBar
    private lateinit var btnToggleProtection: Button
    private lateinit var btnLanguage: Button

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                startProtection(data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences
        prefManager = PreferenceManager(this)

        // Apply saved language
        LanguageHelper.setLanguage(this, prefManager.language)

        setContentView(R.layout.activity_main)

        initializeViews()
        setupUI()
        setupListeners()
        loadPreferences()
        updateUIState()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun initializeViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvStatus = findViewById(R.id.tvStatus)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnSettings = findViewById(R.id.btnSettings)
        spinnerMode = findViewById(R.id.spinnerMode)
        radioGroupFilter = findViewById(R.id.radioGroupFilter)
        tvBlurLabel = findViewById(R.id.tvBlurLabel)
        tvBlurValue = findViewById(R.id.tvBlurValue)
        seekBarBlur = findViewById(R.id.seekBarBlur)
        btnToggleProtection = findViewById(R.id.btnToggleProtection)
        btnLanguage = findViewById(R.id.btnLanguage)
    }

    private fun setupUI() {
        // Setup mode spinner
        val modeAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.detection_modes,
            android.R.layout.simple_spinner_item
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = modeAdapter

        // Setup settings button
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupListeners() {
        // Mode selection
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefManager.detectionMode = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Filter target
        radioGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioFilterWomen -> prefManager.filterTarget = PreferenceManager.FILTER_WOMEN
                R.id.radioFilterMen -> prefManager.filterTarget = PreferenceManager.FILTER_MEN
            }
        }

        // Blur intensity
        seekBarBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actualProgress = if (progress < 1) 1 else progress
                tvBlurValue.text = actualProgress.toString()
                prefManager.blurIntensity = actualProgress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Toggle protection button
        btnToggleProtection.setOnClickListener {
            if (isRunning) {
                stopProtection()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Language switch
        btnLanguage.setOnClickListener {
            val newLang = if (prefManager.language == PreferenceManager.LANG_ENGLISH) {
                PreferenceManager.LANG_ARABIC
            } else {
                PreferenceManager.LANG_ENGLISH
            }
            prefManager.language = newLang
            LanguageHelper.applyLanguage(this, newLang)
        }
    }

    private fun loadPreferences() {
        spinnerMode.setSelection(prefManager.detectionMode)

        when (prefManager.filterTarget) {
            PreferenceManager.FILTER_WOMEN -> findViewById<View>(R.id.radioFilterWomen).performClick()
            PreferenceManager.FILTER_MEN -> findViewById<View>(R.id.radioFilterMen).performClick()
        }

        val blurValue = if (prefManager.blurIntensity < 1) 5 else prefManager.blurIntensity
        seekBarBlur.progress = blurValue
        tvBlurValue.text = blurValue.toString()

        isRunning = prefManager.isProtectionRunning
    }

    private fun updateUIState() {
        if (isRunning) {
            tvStatus.text = getString(R.string.status_running)
            statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
            btnToggleProtection.text = getString(R.string.stop_protection)

            // Disable controls while running
            spinnerMode.isEnabled = false
            radioGroupFilter.isEnabled = false
            for (i in 0 until radioGroupFilter.childCount) {
                radioGroupFilter.getChildAt(i).isEnabled = false
            }
            seekBarBlur.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.status_idle)
            statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            btnToggleProtection.text = getString(R.string.start_protection)

            // Enable controls
            spinnerMode.isEnabled = true
            radioGroupFilter.isEnabled = true
            for (i in 0 until radioGroupFilter.childCount) {
                radioGroupFilter.getChildAt(i).isEnabled = true
            }
            seekBarBlur.isEnabled = true
        }
    }

    private fun checkPermissionsAndStart() {
        // Check overlay permission
        if (!PermissionHelper.hasOverlayPermission(this)) {
            PermissionHelper.requestOverlayPermission(this)
            return
        }

        // Check accessibility permission (recommended but not required)
        if (prefManager.detectionMode == PreferenceManager.MODE_HYBRID &&
            !PermissionHelper.isAccessibilityServiceEnabled(this)) {
            PermissionHelper.requestAccessibilityPermission(this)
            return
        }

        // Request media projection
        PermissionHelper.requestMediaProjection(this, mediaProjectionLauncher)
    }

    private fun startProtection(mediaProjectionData: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        serviceIntent.putExtra("mediaProjectionData", mediaProjectionData)

        startForegroundService(serviceIntent)

        isRunning = true
        prefManager.isProtectionRunning = true
        updateUIState()
    }

    private fun stopProtection() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)

        isRunning = false
        prefManager.isProtectionRunning = false
        updateUIState()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            PermissionHelper.REQUEST_OVERLAY_PERMISSION -> {
                if (PermissionHelper.hasOverlayPermission(this)) {
                    checkPermissionsAndStart()
                }
            }
        }
    }
}