package com.homecam.app.ui

import android.os.Bundle
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
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
            val detectionCategory = PreferenceCategory(context).apply {
                title = getString(R.string.pref_category_detection)
            }
            screen.addPreference(detectionCategory)

            val motionDetection = SwitchPreferenceCompat(context).apply {
                key = "motion_detection"
                title = getString(R.string.pref_motion_detection)
                setDefaultValue(true)
            }
            detectionCategory.addPreference(motionDetection)

            val detectionInterval = ListPreference(context).apply {
                key = "detection_interval"
                title = getString(R.string.pref_detection_interval)
                entries = arrayOf("每 1 帧", "每 2 帧", "每 3 帧", "每 5 帧", "每 10 帧")
                entryValues = arrayOf("1", "2", "3", "5", "10")
                setDefaultValue("3")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            detectionCategory.addPreference(detectionInterval)

            val fallDetection = SwitchPreferenceCompat(context).apply {
                key = "fall_detection"
                title = getString(R.string.pref_fall_detection)
                summary = getString(R.string.pref_fall_detection_summary)
                setDefaultValue(false)
            }
            detectionCategory.addPreference(fallDetection)

            val phoneDetection = SwitchPreferenceCompat(context).apply {
                key = "phone_detection"
                title = getString(R.string.pref_phone_detection)
                summary = getString(R.string.pref_phone_detection_summary)
                setDefaultValue(false)
            }
            detectionCategory.addPreference(phoneDetection)

            val cryDetection = SwitchPreferenceCompat(context).apply {
                key = "cry_detection"
                title = getString(R.string.pref_cry_detection)
                summary = getString(R.string.pref_cry_detection_summary)
                setDefaultValue(false)
            }
            detectionCategory.addPreference(cryDetection)

            val sleepDetection = SwitchPreferenceCompat(context).apply {
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

            // 迁移旧版 Int 值为 String（SeekBarPreference→ListPreference）
            preferenceManager.sharedPreferences?.let { prefs ->
                val editor = prefs.edit()
                prefs.all["save_duration"]?.let { v ->
                    if (v is Int) editor.putString("save_duration", v.toString())
                }
                prefs.all["max_video_count"]?.let { v ->
                    if (v is Int) editor.putString("max_video_count", v.toString())
                }
                editor.apply()
            }

            val saveDuration = androidx.preference.ListPreference(context).apply {
                key = "save_duration"
                title = getString(R.string.pref_save_duration)
                entries = arrayOf("2", "3", "4", "5")
                entryValues = arrayOf("2", "3", "4", "5")
                setDefaultValue("3")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            }
            recordingCategory.addPreference(saveDuration)

            val maxVideoCount = androidx.preference.ListPreference(context).apply {
                key = "max_video_count"
                title = getString(R.string.pref_max_video_count)
                entries = arrayOf("10", "50", "100", "200", "500", "1000")
                entryValues = arrayOf("10", "50", "100", "200", "500", "1000")
                setDefaultValue("50")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            }
            recordingCategory.addPreference(maxVideoCount)

            preferenceScreen = screen

            // Manual dependency: disable sub-switches when motion detection is off
            val motionDeps = mapOf(
                fallDetection to R.string.pref_fall_detection_summary,
                phoneDetection to R.string.pref_phone_detection_summary
            )
            fun updateMotionDeps(enabled: Boolean) {
                for ((dep, summaryId) in motionDeps) {
                    dep.isEnabled = enabled
                    dep.summary = getString(if (enabled) summaryId else R.string.pref_depends_motion)
                }
            }
            motionDetection.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    updateMotionDeps(newValue as Boolean)
                    true
                }
            updateMotionDeps(motionDetection.isChecked)
            Log.d("SettingsFragment", "onCreatePreferences complete")
        }
    }
}
