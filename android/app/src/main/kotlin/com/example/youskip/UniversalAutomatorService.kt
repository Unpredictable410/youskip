//package com.example.youskip
//
//import android.accessibilityservice.AccessibilityService
//import android.view.accessibility.AccessibilityEvent
//import android.view.accessibility.AccessibilityNodeInfo
//import android.widget.Toast
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.PendingIntent
//import android.content.Context
//import android.content.Intent
//import android.content.BroadcastReceiver
//import android.content.IntentFilter
//import android.os.Build
//import android.os.Handler
//import android.os.Looper
//
//class UniversalAutomatorService : AccessibilityService() {
//
//    companion object {
//        var isMasterSwitchOn: Boolean = true
//    }
//
//    private val NOTIFICATION_ID = 9999
//    private val CHANNEL_ID = "YouSkipStatusChannel"
//    private val TOGGLE_ACTION = "com.example.youskip.TOGGLE_ACTION"
//
//    private val executionHandler = Handler(Looper.getMainLooper())
//    private var playlistBypassLock = false
//
//    private var lastSkipClickTime: Long = 0
//    private var lastBypassTime: Long = 0
//    private var lastEventProcessTime: Long = 0
//
//    private val toggleReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == TOGGLE_ACTION) {
//                isMasterSwitchOn = !isMasterSwitchOn
//                val statusMessage = if (isMasterSwitchOn) "YouSkip Resumed!" else "YouSkip Paused."
//                Toast.makeText(applicationContext, statusMessage, Toast.LENGTH_SHORT).show()
//                showPersistentNotification()
//            }
//        }
//    }
//
//    override fun onServiceConnected() {
//        super.onServiceConnected()
//        val filter = IntentFilter(TOGGLE_ACTION)
//
//        if (Build.VERSION.SDK_INT >= 33) {
//            registerReceiver(toggleReceiver, filter, 2)
//        } else {
//            registerReceiver(toggleReceiver, filter)
//        }
//
//        showPersistentNotification()
//    }
//
//    override fun onAccessibilityEvent(event: AccessibilityEvent) {
//        if (!isMasterSwitchOn) return
//
//        val currentTime = System.currentTimeMillis()
//        if (currentTime - lastEventProcessTime < 300) {
//            return
//        }
//        lastEventProcessTime = currentTime
//
//        val sourceNode = event.source
//        if (sourceNode != null) {
//            if (huntForSkipButton(sourceNode)) return
//            if (!playlistBypassLock && isUnskippableAd(sourceNode)) {
//                if (executePlaylistBypass(sourceNode)) return
//            }
//        }
//
//        val activeRoot = rootInActiveWindow
//        if (activeRoot != null) {
//            if (huntForSkipButton(activeRoot)) return
//            if (!playlistBypassLock && isUnskippableAd(activeRoot)) {
//                if (executePlaylistBypass(activeRoot)) return
//            }
//        }
//
//        val allWindows = windows
//        for (window in allWindows) {
//            val rootNode = window.root ?: continue
//            if (huntForSkipButton(rootNode)) return
//            if (!playlistBypassLock && isUnskippableAd(rootNode)) {
//                if (executePlaylistBypass(rootNode)) return
//            }
//        }
//    }
//
//    // ==========================================
//    // 📏 THE PHYSICAL RULER
//    // ==========================================
//    // Changed this to require a NON-NULL node to satisfy the compiler
//    private fun isActuallyOnScreen(node: AccessibilityNodeInfo): Boolean {
//        val bounds = android.graphics.Rect()
//        node.getBoundsInScreen(bounds)
//        return bounds.width() > 0 && bounds.height() > 0
//    }
//
//    // ==========================================
//    // 🎯 PRECISION SNIPER
//    // ==========================================
//    private fun huntForSkipButton(node: AccessibilityNodeInfo?): Boolean {
//        // Explicit null check to trigger Kotlin's Smart Cast
//        if (node == null) return false
//        if (!isActuallyOnScreen(node)) return false
//
//        val currentTime = System.currentTimeMillis()
//        val viewId = node.viewIdResourceName?.lowercase() ?: ""
//        val visibleText = node.text?.toString()?.lowercase() ?: ""
//
//        val isSkipId = viewId.contains("skip_ad_button") || viewId.contains("skip_button") || viewId.contains("ad_action_button")
//        val isExactSkipText = visibleText == "skip ad" || visibleText == "skip" || visibleText == "skip ads"
//
//        if (isSkipId || isExactSkipText) {
//
//            if (currentTime - lastSkipClickTime < 5000) {
//                return false
//            }
//
//            var target: AccessibilityNodeInfo? = node
//            var successfullyClicked = false
//            while (target != null) {
//                if (target.isClickable) {
//                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                    successfullyClicked = true
//                    break
//                }
//                target = target.parent
//            }
//
//            if (!successfullyClicked) {
//                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//            }
//
//            lastSkipClickTime = currentTime
//            Toast.makeText(applicationContext, "YouSkip: Smashed the Skip Button!", Toast.LENGTH_SHORT).show()
//            return true
//        }
//
//        for (i in 0 until node.childCount) {
//            if (huntForSkipButton(node.getChild(i))) return true
//        }
//        return false
//    }
//
//    // ==========================================
//    // 🛡️ STRICT AD DETECTOR
//    // ==========================================
//    private fun isUnskippableAd(node: AccessibilityNodeInfo?): Boolean {
//        // Explicit null check
//        if (node == null) return false
//        if (!isActuallyOnScreen(node)) return false
//
//        val viewId = node.viewIdResourceName?.lowercase() ?: ""
//        val visibleText = node.text?.toString()?.lowercase() ?: ""
//
//        val isSponsoredText = visibleText == "sponsored" || visibleText.startsWith("sponsored ·")
//        val isAdBadge = viewId.contains("ad_badge") || viewId.contains("ad_progress")
//
//        if (isSponsoredText || isAdBadge) {
//            return true
//        }
//
//        for (i in 0 until node.childCount) {
//            if (isUnskippableAd(node.getChild(i))) return true
//        }
//        return false
//    }
//
//    private fun findNodeByResourceOrDesc(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
//        // Explicit null check
//        if (node == null) return null
//        if (!isActuallyOnScreen(node)) return null
//
//        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
//        val viewId = node.viewIdResourceName?.lowercase() ?: ""
//
//        if (desc.contains(target) || viewId.contains(target)) {
//            var interactiveTarget: AccessibilityNodeInfo? = node
//            while (interactiveTarget != null && !interactiveTarget.isClickable) {
//                interactiveTarget = interactiveTarget.parent
//            }
//            if (interactiveTarget?.isClickable == true) return interactiveTarget
//        }
//        for (i in 0 until node.childCount) {
//            val result = findNodeByResourceOrDesc(node.getChild(i), target)
//            if (result != null) return result
//        }
//        return null
//    }
//
//    private fun executePlaylistBypass(rootNode: AccessibilityNodeInfo): Boolean {
//        val currentTime = System.currentTimeMillis()
//
//        if (currentTime - lastBypassTime < 15000) {
//            return false
//        }
//
//        val nextButton = findNodeByResourceOrDesc(rootNode, "next")
//        if (nextButton != null) {
//            playlistBypassLock = true
//            lastBypassTime = currentTime
//
//            Toast.makeText(applicationContext, "YouSkip: Playlist Bypass!", Toast.LENGTH_SHORT).show()
//            nextButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//
//            executionHandler.postDelayed({
//                val newActiveRoot = rootInActiveWindow
//                val prevButton = findNodeByResourceOrDesc(newActiveRoot, "previous")
//                prevButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//
//                executionHandler.postDelayed({ playlistBypassLock = false }, 2000)
//            }, 1000)
//
//            return true
//        }
//        return false
//    }
//
//    // ==========================================
//    // 🎛️ NOTIFICATION SYSTEM
//    // ==========================================
//    private fun showPersistentNotification() {
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(CHANNEL_ID, "YouSkip Active Status", NotificationManager.IMPORTANCE_DEFAULT)
//            notificationManager.createNotificationChannel(channel)
//        }
//        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
//        val pendingLaunchIntent = launchIntent?.let {
//            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
//        }
//        val toggleIntent = Intent(TOGGLE_ACTION).setPackage(packageName)
//        val pendingToggleIntent = PendingIntent.getBroadcast(
//            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//        val titleText = if (isMasterSwitchOn) "YouSkip is Active" else "YouSkip is Paused"
//        val descText = if (isMasterSwitchOn) "Scanning for ads..." else "Ad scanning disabled."
//        val buttonText = if (isMasterSwitchOn) "PAUSE" else "RESUME"
//        val icon = if (isMasterSwitchOn) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
//
//        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            Notification.Builder(this, CHANNEL_ID)
//        } else {
//            @Suppress("DEPRECATION")
//            Notification.Builder(this)
//        }
//        val notification = notificationBuilder
//            .setContentTitle(titleText)
//            .setContentText(descText)
//            .setSmallIcon(icon)
//            .setContentIntent(pendingLaunchIntent)
//            .setOngoing(true)
//            .addAction(0, buttonText, pendingToggleIntent)
//            .build()
//        notificationManager.notify(NOTIFICATION_ID, notification)
//    }
//
//    override fun onInterrupt() {
//        playlistBypassLock = false
//        executionHandler.removeCallbacksAndMessages(null)
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        try { unregisterReceiver(toggleReceiver) } catch (e: Exception) {}
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.cancel(NOTIFICATION_ID)
//        executionHandler.removeCallbacksAndMessages(null)
//    }
//}

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
import android.os.Handler
import android.os.Looper

class UniversalAutomatorService : AccessibilityService() {

    companion object {
        var isMasterSwitchOn: Boolean = true
    }

    private val NOTIFICATION_ID = 9999
    private val CHANNEL_ID = "YouSkipStatusChannel"
    private val TOGGLE_ACTION = "com.example.youskip.TOGGLE_ACTION"

    private val executionHandler = Handler(Looper.getMainLooper())
    private var playlistBypassLock = false

    private var lastSkipClickTime: Long = 0
    private var lastBypassTime: Long = 0
    private var lastEventProcessTime: Long = 0

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TOGGLE_ACTION) {
                isMasterSwitchOn = !isMasterSwitchOn
                val statusMessage = if (isMasterSwitchOn) "YouSkip Resumed!" else "YouSkip Paused."
                Toast.makeText(applicationContext, statusMessage, Toast.LENGTH_SHORT).show()
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

        // 1. Check who fired the event. (This works for the PiP mini-player too!)
        val sourcePackage = event.packageName?.toString() ?: ""
        if (!sourcePackage.contains("com.google.android.youtube")) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventProcessTime < 300) return
        lastEventProcessTime = currentTime

        // 2. Grab the specific node that triggered the event
        val eventNode = event.source ?: return

        // 3. Get the root of THAT specific window (This isolates the PiP window)
        val windowRoot = eventNode.window?.root ?: eventNode

        // Priority 1: ALWAYS look for the Skip Button first
        if (huntForSkipButton(windowRoot)) return

        // Priority 2: Execute Unskippable Bypass ONLY if it's truly unskippable
        if (!playlistBypassLock && isUnskippableAd(windowRoot)) {
            routeAdBypass(windowRoot)
            return
        }
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

        val isSkipId = viewId.contains("skip_ad_button") || viewId.contains("skip_button") || viewId.contains("ad_action_button")
        val isExactSkipText = visibleText.contains("skip ad") || visibleText == "skip"

        if (isSkipId || isExactSkipText) {
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
            return true
        }

        for (i in 0 until node.childCount) {
            if (huntForSkipButton(node.getChild(i))) return true
        }
        return false
    }

    // ==========================================
    // 🧠 SMART AD DETECTOR (The Patience Update)
    // ==========================================

    // Checks if the ad has a "Skip in 5" countdown
    private fun isWaitingForSkipButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !isActuallyOnScreen(node)) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (viewId.contains("ad_countdown") || text.contains("skip in") || text.contains("will begin in")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (isWaitingForSkipButton(node.getChild(i))) return true
        }
        return false
    }

    private fun isUnskippableAd(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // THE FIX: If there is a countdown timer anywhere on screen, abort the bypass and wait!
        if (isWaitingForSkipButton(node)) return false

        if (!isActuallyOnScreen(node)) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val visibleText = node.text?.toString()?.lowercase() ?: ""

        val isSponsoredText = visibleText.contains("sponsored") || visibleText.contains("ad ·")
        val isAdBadge = viewId.contains("ad_badge") || viewId.contains("ad_progress")

        if (isSponsoredText || isAdBadge) return true

        for (i in 0 until node.childCount) {
            if (isUnskippableAd(node.getChild(i))) return true
        }
        return false
    }

    private fun findNodeByResourceOrDesc(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (!isActuallyOnScreen(node)) return null

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc.contains(target) || viewId.contains(target)) {
            var interactiveTarget: AccessibilityNodeInfo? = node
            while (interactiveTarget != null && !interactiveTarget.isClickable) {
                interactiveTarget = interactiveTarget.parent
            }
            if (interactiveTarget?.isClickable == true) return interactiveTarget
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByResourceOrDesc(node.getChild(i), target)
            if (result != null) return result
        }
        return null
    }

    // ==========================================
    // 🚦 SMART BYPASS ROUTER
    // ==========================================
    private fun routeAdBypass(rootNode: AccessibilityNodeInfo) {
        val currentTime = System.currentTimeMillis()

        // Increased cooldown to 20 seconds to prevent total chaos if YouTube glitches
        if (currentTime - lastBypassTime < 20000) return

        val isPlaylistActive = findNodeByResourceOrDesc(rootNode, "playlist") != null

        if (isPlaylistActive) {
            executePlaylistBypass(rootNode, currentTime)
        } else {
            executeCloseAndReopenBypass(rootNode, currentTime)
        }
    }

    private fun executePlaylistBypass(rootNode: AccessibilityNodeInfo, currentTime: Long) {
        val nextButton = findNodeByResourceOrDesc(rootNode, "next") ?: return

        playlistBypassLock = true
        lastBypassTime = currentTime
        Toast.makeText(applicationContext, "YouSkip: Playlist Bypass!", Toast.LENGTH_SHORT).show()

        nextButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        executionHandler.postDelayed({
            val newActiveRoot = rootInActiveWindow ?: return@postDelayed
            val prevButton = findNodeByResourceOrDesc(newActiveRoot, "previous")
            prevButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            executionHandler.postDelayed({ playlistBypassLock = false }, 2000)
        }, 1000)
    }

    // THE UPGRADED REBOOT SEQUENCE
    private fun executeCloseAndReopenBypass(rootNode: AccessibilityNodeInfo, currentTime: Long) {
        playlistBypassLock = true
        lastBypassTime = currentTime
        Toast.makeText(applicationContext, "YouSkip: Rebooting Video...", Toast.LENGTH_SHORT).show()

        // 1. Minimize the video (triggers the shrink animation)
        performGlobalAction(GLOBAL_ACTION_BACK)

        // INCREASED DELAY: Wait 1200ms to let the mini-player animation completely finish and settle
        executionHandler.postDelayed({
            val newRoot = rootInActiveWindow ?: return@postDelayed

            // TARGETED SEARCH: Specifically hunt for YouTube's mini-player close button IDs
            val closeButton = findNodeByResourceOrDesc(newRoot, "close")
                ?: findNodeByResourceOrDesc(newRoot, "dismiss")
                ?: findNodeByResourceOrDesc(newRoot, "miniplayer_close")

            closeButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Wait 1000ms for the mini-player to vanish, then strike
            executionHandler.postDelayed({
                val feedRoot = rootInActiveWindow ?: return@postDelayed

                // Strike the video to reopen it
                val firstVideo = findUniversalVideoThumbnail(feedRoot)
                firstVideo?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                playlistBypassLock = false
            }, 1000)
        }, 1200)
    }

    // THE UPGRADED VIDEO HUNTER
    private fun findUniversalVideoThumbnail(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // This looks for standard video cards, but ignores shorts/ads
        val isVideoThumbnail = (desc.contains("views") || desc.contains("ago") || viewId.contains("video_thumbnail"))
        val isNotAd = !desc.contains("sponsored") && !desc.contains("ad ·")

        if (isVideoThumbnail && isNotAd && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val result = findUniversalVideoThumbnail(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    // ==========================================
    // 🎛️ NOTIFICATION SYSTEM
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

    override fun onInterrupt() {
        playlistBypassLock = false
        executionHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(toggleReceiver) } catch (e: Exception) {}
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        executionHandler.removeCallbacksAndMessages(null)
    }
}