package com.example.youskip

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.util.Log

class UniversalAutomatorService : AccessibilityService() {

    companion object {
        var isMasterSwitchOn: Boolean = true
    }
    private val NOTIFICATION_ID = 9999
    private val CHANNEL_ID = "YouSkipStatusChannel"
    private val TOGGLE_ACTION = "com.example.youskip.TOGGLE_ACTION"

    private var isBannerDismissing = false
    private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSkipClickTime: Long = 0
    private var lastEventProcessTime: Long = 0

    // The Receiver now ONLY updates the notification when the Tile is clicked
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TOGGLE_ACTION) {
                // We sync the variable just in case
                isMasterSwitchOn = UniversalAutomatorService.isMasterSwitchOn

            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(TOGGLE_ACTION)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, filter, 2)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        // Bring back the VIP Notification
        showPersistentNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isMasterSwitchOn) return

        val sourcePackage = event.packageName?.toString() ?: ""
        if (!sourcePackage.contains("com.google.android.youtube")) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventProcessTime < 300) return
        lastEventProcessTime = currentTime

        val eventNode = event.source ?: return
        val windowRoot = eventNode.window?.root ?: eventNode

        // --- THE FLAWLESS SNIPER ---
        huntForSkipButton(windowRoot)
        // --- THE BANNER SNIPER ---
        huntForBannerAd(windowRoot)
    }

    private fun isActuallyOnScreen(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0
    }

    // ==========================================
    // 🎯 PRECISION SNIPER
    // ==========================================
    private fun huntForSkipButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!isActuallyOnScreen(node)) return false

        val currentTime = System.currentTimeMillis()
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val visibleText = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        val isSkipId = viewId.contains("skip_ad_button") || viewId.contains("skip_button") || viewId.contains("modern_skip")
        val hasSkipText = (visibleText.contains("skip") || desc.contains("skip"))
        val isNotCountdown = !visibleText.contains("in ") && !desc.contains("in ")

        if (isSkipId || (hasSkipText && isNotCountdown)) {
            if (currentTime - lastSkipClickTime < 5000) return false

            var target: AccessibilityNodeInfo? = node
            var successfullyClicked = false
            while (target != null) {
                if (target.isClickable) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    successfullyClicked = true
                    break
                }
                target = target.parent
            }

            if (!successfullyClicked) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            lastSkipClickTime = currentTime
            Toast.makeText(applicationContext, "YouSkip: Smashed the Skip Button!", Toast.LENGTH_SHORT).show()
            // We assume it takes 5 seconds to show the skip button on a standard ad
            saveAdReceipt("Standard Skippable", 5.0)
            return true
        }

        for (i in 0 until node.childCount) {
            if (huntForSkipButton(node.getChild(i))) return true
        }
        return false
    }

    // ==========================================
    // 🛡️ THE BANNER SNIPER
    // ==========================================
    private fun huntForBannerAd(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || isBannerDismissing) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isDirectClose = viewId.contains("overlay_close_button") ||
                viewId.contains("ad_close_button") ||
                desc == "close ad"

        if (isDirectClose && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        val isAdMenu = viewId.contains("ad_info") || viewId.contains("ad_action_menu") || desc == "ad info"

        if (isAdMenu && node.isClickable) {
            isBannerDismissing = true
            saveAdReceipt("Overlay Banner", 0.0) // Banners don't really have a 'time'
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            bannerHandler.postDelayed({
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    var closeBtn = findNodeByTextOrId(activeRoot, "close")
                    if (closeBtn == null) closeBtn = findNodeByTextOrId(activeRoot, "dismiss")

                    closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                isBannerDismissing = false
            }, 400)
            return true
        }

        for (i in 0 until node.childCount) {
            if (huntForBannerAd(node.getChild(i))) return true
        }
        return false
    }

    private fun findNodeByTextOrId(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (text == targetText || desc == targetText) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByTextOrId(node.getChild(i), targetText)
            if (result != null) return result
        }
        return null
    }

    // ==========================================
    // 🎛️ RESTORED NOTIFICATION SYSTEM
    // ==========================================
    private fun showPersistentNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "YouSkip Active Status", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingLaunchIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val toggleIntent = Intent(TOGGLE_ACTION).setPackage(packageName)
        val pendingToggleIntent = PendingIntent.getBroadcast(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // This will update dynamically based on the switch!
        val titleText = if (isMasterSwitchOn) "YouSkip is Active" else "YouSkip is Paused"
        val descText = if (isMasterSwitchOn) "Scanning for ads..." else "Ad scanning disabled."
        val buttonText = if (isMasterSwitchOn) "PAUSE" else "RESUME"
        val icon = if (isMasterSwitchOn) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setContentTitle(titleText)
            .setContentText(descText)
            .setSmallIcon(icon)
            .setContentIntent(pendingLaunchIntent)
            .setOngoing(true)
            .addAction(0, buttonText, pendingToggleIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ==========================================
    // 📊 ANALYTICS LEDGER (24-Hour Cache)
    // ==========================================
    private fun saveAdReceipt(adType: String, timeSavedSec: Double) {
        // We use a specific file name so Flutter can easily find it later
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val currentTime = System.currentTimeMillis()
        // Default to current time if this is the very first run
        val timerStartTime = prefs.getLong("flutter.AnalyticsStartTime", currentTime)

        // Check if 24 hours (86,400,000 milliseconds) have passed
        val timeElapsed = currentTime - timerStartTime
        val isTimerExpired = timeElapsed >= 86400000

        var existingHistory = prefs.getString("flutter.AdHistory", "") ?: ""
        var totalAds = prefs.getInt("flutter.TotalAdsToday", 0)

        if (isTimerExpired) {
            // 24 Hours have passed! Wipe the ledger clean.
            existingHistory = ""
            totalAds = 0
            editor.putLong("flutter.AnalyticsStartTime", currentTime) // Restart the clock
        } else if (timerStartTime == currentTime) {
            // Very first time running, save the start time
            editor.putLong("flutter.AnalyticsStartTime", currentTime)
        }

        // Format the receipt: "Timestamp|AdType|SecondsSaved"
        // We use "||" to separate entries so Flutter can easily split them later
        val newEntry = "$currentTime|$adType|$timeSavedSec"
        val updatedHistory = if (existingHistory.isEmpty()) newEntry else "$existingHistory||$newEntry"

        // Save everything back to the phone's memory
        editor.putString("flutter.AdHistory", updatedHistory)
        editor.putInt("flutter.TotalAdsToday", totalAds + 1)
        editor.apply()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(toggleReceiver) } catch (e: Exception) {}
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}