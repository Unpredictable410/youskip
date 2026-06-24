package com.example.youskip

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val SETTINGS_CHANNEL = "com.universal.automator/settings"
    private val CONFIG_CHANNEL = "com.universal.automator/config"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Channel 1: Accessibility Permissions Handlers
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SETTINGS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "launchSettingsWindow" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    result.success(true)
                }
                "checkActiveStatus" -> {
                    // FIX 1: Explicitly call this@MainActivity so the compiler knows exactly which context to use
                    result.success(isAccessibilityServiceEnabled(this@MainActivity, UniversalAutomatorService::class.java))
                }
                else -> result.notImplemented()
            }
        }

        // Channel 2: Configuration Transmission
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONFIG_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "syncParams") {
                val delay = call.argument<Int>("delayMs") ?: 2000
                result.success(true)
            } else {
                result.notImplemented()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false

        // FIX 2: Replaced the outdated TextUtils.SimpleStringSplitter with modern Kotlin logic
        return enabledServicesSetting.split(':').any { componentNameString ->
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            enabledService != null && enabledService == expectedComponentName
        }
    }
}