package com.tazkia.ai.blurfilter.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tazkia.ai.blurfilter.R
import com.tazkia.ai.blurfilter.databinding.ActivityMainBinding
import com.tazkia.ai.blurfilter.service.ScreenCaptureService
import com.tazkia.ai.blurfilter.utils.PermissionHelper
import com.tazkia.ai.blurfilter.utils.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefManager: PreferenceManager
    private var isRunning = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                startProtection(data)
            }
        } else {
            Log.w("MainActivity", "Media projection permission denied")
            updateUIState()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (PermissionHelper.hasOverlayPermission(this)) {
            Log.i("MainActivity", "Overlay permission granted")
            checkPermissionsAndStart()
        } else {
            Log.w("MainActivity", "Overlay permission denied")
            binding.tvPermissionStatus.text = getString(R.string.permissions_none_granted)
            updatePermissionIndicator(false, PermissionHelper.isAccessibilityServiceEnabled(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences
        prefManager = PreferenceManager(this)

        // Apply saved language
        LanguageHelper.setLanguage(this, prefManager.language)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        loadPreferences()
        updateUIState()
        checkPermissionsStatus() // Check permissions on startup
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
        checkPermissionsStatus() // Check permissions when returning to app
    }

    private fun setupUI() {
        // Setup mode spinner
        val modeAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.detection_modes,
            android.R.layout.simple_spinner_item
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerMode.adapter = modeAdapter

        // Setup settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupListeners() {
        // Mode selection
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefManager.detectionMode = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Filter target
        binding.radioGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioFilterWomen -> prefManager.filterTarget = PreferenceManager.FILTER_WOMEN
                R.id.radioFilterMen -> prefManager.filterTarget = PreferenceManager.FILTER_MEN
            }
        }

        // Blur intensity
        binding.seekBarBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvBlurValue.text = progress.toString()
                prefManager.blurIntensity = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Toggle protection button
        binding.btnToggleProtection.setOnClickListener {
            if (isRunning) {
                stopProtection()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Language switch
        binding.btnLanguage.setOnClickListener {
            val newLang = if (prefManager.language == PreferenceManager.LANG_ENGLISH) {
                PreferenceManager.LANG_ARABIC
            } else {
                PreferenceManager.LANG_ENGLISH
            }
            prefManager.language = newLang
            LanguageHelper.applyLanguage(this, newLang)
        }

        // Refresh permissions button
        binding.btnRefresh.setOnClickListener {
            checkPermissionsStatus()
        }
    }

    private fun loadPreferences() {
        binding.spinnerMode.setSelection(prefManager.detectionMode)

        when (prefManager.filterTarget) {
            PreferenceManager.FILTER_WOMEN -> binding.radioFilterWomen.isChecked = true
            PreferenceManager.FILTER_MEN -> binding.radioFilterMen.isChecked = true
        }

        binding.seekBarBlur.progress = prefManager.blurIntensity
        binding.tvBlurValue.text = prefManager.blurIntensity.toString()

        isRunning = prefManager.isProtectionRunning
    }

    private fun updateUIState() {
        if (isRunning) {
            binding.tvStatus.text = getString(R.string.status_running)
            binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
            binding.btnToggleProtection.text = getString(R.string.stop_protection)

            // Disable controls while running
            binding.spinnerMode.isEnabled = false
            binding.radioGroupFilter.isEnabled = false
            binding.seekBarBlur.isEnabled = false
            binding.btnRefresh.isEnabled = false
        } else {
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            binding.btnToggleProtection.text = getString(R.string.start_protection)

            // Enable controls
            binding.spinnerMode.isEnabled = true
            binding.radioGroupFilter.isEnabled = true
            binding.seekBarBlur.isEnabled = true
            binding.btnRefresh.isEnabled = true
        }
    }

    private fun checkPermissionsAndStart() {
        Log.d("MainActivity", "Checking permissions...")
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Log.i("MainActivity", "Requesting overlay permission")
            PermissionHelper.requestOverlayPermission(this, overlayPermissionLauncher)
            return
        }
        if (prefManager.detectionMode == PreferenceManager.MODE_HYBRID &&
            !PermissionHelper.isAccessibilityServiceEnabled(this)) {
            Log.i("MainActivity", "Requesting accessibility permission")
            PermissionHelper.requestAccessibilityPermission(this)
            return
        }
        Log.i("MainActivity", "Requesting media projection")
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

    private fun checkPermissionsStatus() {
        val overlayGranted = PermissionHelper.hasOverlayPermission(this)
        val accessibilityGranted = PermissionHelper.isAccessibilityServiceEnabled(this)

        // Update permission status text
        val statusText = when {
            overlayGranted && accessibilityGranted -> getString(R.string.permissions_all_granted)
            overlayGranted -> getString(R.string.permissions_overlay_only)
            accessibilityGranted -> getString(R.string.permissions_accessibility_only)
            else -> getString(R.string.permissions_none_granted)
        }

        binding.tvPermissionStatus.text = statusText

        // Update status indicator color based on permissions
        updatePermissionIndicator(overlayGranted, accessibilityGranted)
    }

    private fun updatePermissionIndicator(overlayGranted: Boolean, accessibilityGranted: Boolean) {
        when {
            overlayGranted && accessibilityGranted -> {
                binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
            }
            overlayGranted || accessibilityGranted -> {
                binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_away)
            }
            else -> {
                binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_busy)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PermissionHelper.REQUEST_OVERLAY_PERMISSION -> {
                if (PermissionHelper.hasOverlayPermission(this)) {
                    Log.i("MainActivity", "Overlay permission granted via onActivityResult")
                    checkPermissionsAndStart()
                } else {
                    Log.w("MainActivity", "Overlay permission denied via onActivityResult")
                    binding.tvPermissionStatus.text = getString(R.string.permissions_none_granted)
                    updatePermissionIndicator(false, PermissionHelper.isAccessibilityServiceEnabled(this))
                }
            }
        }
    }
}