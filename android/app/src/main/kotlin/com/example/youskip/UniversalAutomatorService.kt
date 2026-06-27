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

    // --- 📊 ANALYTICS & STOPWATCH CORE VARIABLES ---
    private var isTrackingUnskippable = false
    private var adStartTime: Long = 0
    private var currentAdvertiser: String = "Unknown Advertiser"
    private var currentAdProgress: String = "" // NEW: Tracks "1 of 2"
    private var lastSeenAdProgress: String = "" // NEW: Detects when Ad 1 turns into Ad 2

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TOGGLE_ACTION) {
                isMasterSwitchOn = UniversalAutomatorService.isMasterSwitchOn
                // Refresh the notification layout visually when toggled
                showPersistentNotification()
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

        showPersistentNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isMasterSwitchOn) return

        val sourcePackage = event.packageName?.toString() ?: ""
        if (!sourcePackage.contains("com.google.android.youtube")) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventProcessTime < 300) return
        lastEventProcessTime = currentTime

        // 1. Grab screen root layer securely
        val eventNode = event.source ?: return
        val windowRoot = eventNode.window?.root ?: eventNode

        // 2. Run the active ad screening logic
        val adIsOnScreen = isAdActiveOnScreen(windowRoot)

        // 3. RUN THE STOPWATCH & SCRAPER
        if (adIsOnScreen) {
            // Actively scrape the DOM for brand names and progress text
            extractAdDetails(windowRoot)

            if (!isTrackingUnskippable) {
                // Brand new ad just started
                isTrackingUnskippable = true
                adStartTime = System.currentTimeMillis()
                lastSeenAdProgress = currentAdProgress

            } else if (currentAdProgress == "(2 of 2)" && lastSeenAdProgress == "(1 of 2)") {
                // 🚨 MID-AD TRANSITION DETECTED!
                // Ad 1 just finished, Ad 2 just started. Log Ad 1 instantly!
                val timeElapsedSeconds = (System.currentTimeMillis() - adStartTime) / 1000.0
                saveAdReceipt("Unskippable Ad $lastSeenAdProgress", timeElapsedSeconds, currentAdvertiser)

                // Reset the clock and variables for Ad 2
                adStartTime = System.currentTimeMillis()
                lastSeenAdProgress = currentAdProgress
                currentAdvertiser = "Unknown Advertiser"
            }

        } else if (isTrackingUnskippable) {
            // Ad cleared screen entirely
            if (adStartTime > 0) {
                val timeElapsedSeconds = (System.currentTimeMillis() - adStartTime) / 1000.0

                if (timeElapsedSeconds > 3.0) {
                    val typePrefix = if (lastSeenAdProgress.isNotEmpty()) " $lastSeenAdProgress" else ""

                    // NEW LOGIC: Distinguish between standard unskippable and short auto-skipped ads
                    val adType = if (timeElapsedSeconds <= 16.0) {
                        "Short Auto-Skipped$typePrefix"
                    } else {
                        "Unskippable Ad$typePrefix"
                    }

                    saveAdReceipt(adType, timeElapsedSeconds, currentAdvertiser)
                }
            }

            // Clean state tracking variables for the next video
            isTrackingUnskippable = false
            adStartTime = 0
            currentAdvertiser = "Unknown Advertiser"
            currentAdProgress = ""
            lastSeenAdProgress = ""
        }

        // 4. Fire target automated actions
        huntForSkipButton(windowRoot)
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

            // ---------------------------------------------------------
            // THE DOUBLE-LOG SHIELD
            // ---------------------------------------------------------
            if (isTrackingUnskippable) {
                val timeToSnipe = (System.currentTimeMillis() - adStartTime) / 1000.0

                // Add the (1 of 2) tag to the skip receipt if it exists!
                val skipLabel = if (currentAdProgress.isNotEmpty()) "Standard Skippable $currentAdProgress" else "Standard Skippable"
                saveAdReceipt(skipLabel, timeToSnipe, currentAdvertiser)

                isTrackingUnskippable = false
                adStartTime = 0
                currentAdvertiser = "Unknown Advertiser"
                currentAdProgress = "" // Clear it out
            } else {
                saveAdReceipt("Standard Skippable", 5.0, "Unknown Advertiser")
            }
            // ---------------------------------------------------------

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
            saveAdReceipt("Overlay Banner", 0.0, "Overlay Ad")
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

    private fun extractAdDetails(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val lowerText = text.lowercase()
        val lowerDesc = desc.lowercase()

        // 1. Detect if it's a Double Ad (1 of 2, 2 of 2)
        if (lowerText.contains("1 of 2") || lowerDesc.contains("1 of 2")) {
            currentAdProgress = "(1 of 2)"
        } else if (lowerText.contains("2 of 2") || lowerDesc.contains("2 of 2")) {
            currentAdProgress = "(2 of 2)"
        }

        // 2. Aggressively Hunt for Advertiser Name
        if (currentAdvertiser == "Unknown Advertiser") {
            if (lowerText.startsWith("sponsored · ")) {
                currentAdvertiser = text.substring(12).trim()
            } else if (lowerText.startsWith("ad · ")) {
                currentAdvertiser = text.substring(5).trim()
            } else if (lowerDesc.contains("visit site") && lowerDesc.length > 11) {
                // YouTube sometimes hides the brand in the CTA button: "Visit site, Samsung"
                currentAdvertiser = desc.replace("Visit site,", "").replace("visit site", "").trim()
            }

            // Clean up massive strings just in case it grabbed a whole paragraph
            if (currentAdvertiser.length > 25) {
                currentAdvertiser = currentAdvertiser.substring(0, 25) + "..."
            }
        }

        for (i in 0 until node.childCount) {
            extractAdDetails(node.getChild(i))
        }
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
    private fun saveAdReceipt(adType: String, timeSavedSec: Double, advertiser: String) {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val currentTime = System.currentTimeMillis()
        val timerStartTime = prefs.getLong("flutter.AnalyticsStartTime", currentTime)

        val timeElapsed = currentTime - timerStartTime
        val isTimerExpired = timeElapsed >= 86400000

        var existingHistory = prefs.getString("flutter.AdHistory", "") ?: ""
        var totalAds = prefs.getInt("flutter.TotalAdsToday", 0)

        if (isTimerExpired) {
            existingHistory = ""
            totalAds = 0
            editor.putLong("flutter.AnalyticsStartTime", currentTime)
        } else if (timerStartTime == currentTime) {
            editor.putLong("flutter.AnalyticsStartTime", currentTime)
        }

        // Formats data payload safely using a 4-part string schema separated by pipes
        val newEntry = "$currentTime|$adType|$timeSavedSec|$advertiser"
        val updatedHistory = if (existingHistory.isEmpty()) newEntry else "$existingHistory||$newEntry"

        editor.putString("flutter.AdHistory", updatedHistory)
        editor.putInt("flutter.TotalAdsToday", totalAds + 1)
        editor.apply()
    }

    // ==========================================
    // 🔍 COMPONENT VIEW SCANNER (Pure Scanner)
    // ==========================================
    private fun isAdActiveOnScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (viewId.contains("ad_progress") ||
            viewId.contains("ad_badge") ||
            viewId.contains("player_learn_more_button") ||
            viewId.contains("ad_cta") ||
            text.startsWith("ad ·") ||
            text.contains("sponsored") ||
            text.contains("video will play after")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (isAdActiveOnScreen(node.getChild(i))) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(toggleReceiver) } catch (e: Exception) {}
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}