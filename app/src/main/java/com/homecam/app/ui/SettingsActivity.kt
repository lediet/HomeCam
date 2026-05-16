package com.homecam.app.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.homecam.app.R

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() start")
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_settings)
            Log.d(TAG, "setContentView done")
        } catch (e: Exception) {
            Log.e(TAG, "setContentView FAILED", e)
            return
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        Log.d(TAG, "onCreate() complete")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            Log.d("SettingsFragment", "onCreatePreferences start")
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            // Video category
            val videoCategory = androidx.preference.PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_video)
            }
            screen.addPreference(videoCategory)

            val scaleFactor = androidx.preference.ListPreference(context).apply {
                key = "scale_factor"
                title = getString(R.string.pref_scale_factor)
                entries = arrayOf(
                    getString(R.string.pref_scale_factor_1x),
                    getString(R.string.pref_scale_factor_075x),
                    getString(R.string.pref_scale_factor_05x)
                )
                entryValues = arrayOf("1.0", "0.75", "0.5")
                setDefaultValue("0.75")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            }
            videoCategory.addPreference(scaleFactor)

            val fps = androidx.preference.ListPreference(context).apply {
                key = "fps"
                title = getString(R.string.pref_fps)
                entries = arrayOf("15 FPS", "30 FPS")
                entryValues = arrayOf("15", "30")
                setDefaultValue("15")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            }
            videoCategory.addPreference(fps)

            // Network category
            val networkCategory = androidx.preference.PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_network)
            }
            screen.addPreference(networkCategory)

            val webPort = androidx.preference.EditTextPreference(context).apply {
                key = "web_port"
                title = getString(R.string.pref_web_port)
                setDefaultValue("8080")
                summaryProvider = androidx.preference.EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            networkCategory.addPreference(webPort)

            // Detection category
            val detectionCategory = androidx.preference.PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_detection)
            }
            screen.addPreference(detectionCategory)

            val motionDetection = androidx.preference.SwitchPreferenceCompat(context).apply {
                key = "motion_detection"
                title = getString(R.string.pref_motion_detection)
                setDefaultValue(true)
            }
            detectionCategory.addPreference(motionDetection)

            val detectionInterval = androidx.preference.ListPreference(context).apply {
                key = "detection_interval"
                title = getString(R.string.pref_detection_interval)
                entries = arrayOf("每 1 帧", "每 2 帧", "每 3 帧", "每 5 帧", "每 10 帧")
                entryValues = arrayOf("1", "2", "3", "5", "10")
                setDefaultValue("3")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            }
            detectionCategory.addPreference(detectionInterval)

            val cryDetection = androidx.preference.SwitchPreferenceCompat(context).apply {
                key = "cry_detection"
                title = getString(R.string.pref_cry_detection)
                summary = getString(R.string.pref_cry_detection_summary)
                setDefaultValue(false)
            }
            detectionCategory.addPreference(cryDetection)

            val sleepDetection = androidx.preference.SwitchPreferenceCompat(context).apply {
                key = "sleep_detection"
                title = getString(R.string.pref_sleep_detection)
                summary = getString(R.string.pref_sleep_detection_summary)
                setDefaultValue(false)
            }
            detectionCategory.addPreference(sleepDetection)

            // Recording category
            val recordingCategory = androidx.preference.PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_recording)
            }
            screen.addPreference(recordingCategory)

            val saveDuration = androidx.preference.SeekBarPreference(context).apply {
                key = "save_duration"
                title = getString(R.string.pref_save_duration)
                min = 2
                max = 5
                setDefaultValue(3)
                summary = getString(R.string.pref_save_duration_summary, 3)
                updateSummary { value ->
                    getString(R.string.pref_save_duration_summary, value)
                }
            }
            recordingCategory.addPreference(saveDuration)

            val maxVideoCount = androidx.preference.SeekBarPreference(context).apply {
                key = "max_video_count"
                title = getString(R.string.pref_max_video_count)
                min = 10
                max = 100
                setDefaultValue(50)
                seekBarIncrement = 10
                summary = getString(R.string.pref_max_video_count_summary, 50)
                updateSummary { value ->
                    getString(R.string.pref_max_video_count_summary, value)
                }
            }
            recordingCategory.addPreference(maxVideoCount)

            preferenceScreen = screen
            Log.d("SettingsFragment", "onCreatePreferences complete")
        }

        private fun androidx.preference.SeekBarPreference.updateSummary(provider: (Int) -> String) {
            setOnPreferenceChangeListener { pref, newValue ->
                (pref as androidx.preference.SeekBarPreference).summary = provider((newValue as Number).toInt())
                true
            }
        }
    }
}
