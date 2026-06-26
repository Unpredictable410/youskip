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

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SETTINGS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "launchSettingsWindow" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                }
                "checkSystemPermission" -> {
                    // Check if Accessibility is actually ON in settings
                    result.success(isAccessibilityServiceEnabled(this@MainActivity, UniversalAutomatorService::class.java))
                }
                "toggleSoftPause" -> {
                    // Sends the signal to flip the Master Switch in your Service
                    val toggleIntent = Intent("com.example.youskip.TOGGLE_ACTION")
                    toggleIntent.setPackage(packageName)
                    sendBroadcast(toggleIntent)
                    result.success(true)
                }
                "getSoftPauseState" -> {
                    // Returns whether the Sniper is currently running or paused
                    result.success(UniversalAutomatorService.isMasterSwitchOn)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false

        return enabledServicesSetting.split(':').any { componentNameString ->
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            enabledService != null && enabledService == expectedComponentName
        }
    }
}