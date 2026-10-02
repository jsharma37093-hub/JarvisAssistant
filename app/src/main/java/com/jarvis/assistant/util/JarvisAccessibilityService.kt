package com.jarvis.assistant.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Optional UI automation bridge used only for explicit JARVIS commands.
 * The user must enable JARVIS in Android Accessibility settings.
 */
class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "JarvisA11y"
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction: PendingAction? = null
    private var attempts = 0

    sealed class PendingAction {
        data class WhatsAppSend(val message: String) : PendingAction()
        data class YouTubeFirstResult(val query: String) : PendingAction()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingAction == null) return
        // Give the target app time to render, then attempt the action.
        scheduleAttempt(250)
    }

    override fun onInterrupt() {
        pendingAction = null
        attempts = 0
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun automateWhatsAppSend(message: String): Boolean {
        if (message.isBlank()) return false
        pendingAction = PendingAction.WhatsAppSend(message)
        attempts = 0
        scheduleAttempt(700)
        return true
    }

    fun automateYouTubeFirstResult(query: String): Boolean {
        if (query.isBlank()) return false
        pendingAction = PendingAction.YouTubeFirstResult(query)
        attempts = 0
        scheduleAttempt(1200)
        return true
    }

    private fun scheduleAttempt(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performPendingAction() }, delayMs)
    }

    private fun performPendingAction() {
        val action = pendingAction ?: return
        val root = rootInActiveWindow ?: run {
            retry()
            return
        }

        val success = when (action) {
            is PendingAction.WhatsAppSend -> clickWhatsAppSend(root)
            is PendingAction.YouTubeFirstResult -> clickFirstYouTubeResult(root)
        }

        if (success) {
            pendingAction = null
            attempts = 0
        } else {
            retry()
        }
    }

    private fun retry() {
        attempts++
        if (attempts >= 20) {
            Log.w(TAG, "UI automation timed out")
            pendingAction = null
            attempts = 0
            return
        }
        scheduleAttempt(400)
    }

    private fun clickWhatsAppSend(root: AccessibilityNodeInfo): Boolean {
        // WhatsApp normally exposes the composer as an EditText and the Send button
        // as a button/content-description. We only click a node that is clearly a Send action.
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, candidates)
        val send = candidates.firstOrNull { node ->
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            (text == "send" || desc == "send" || desc.contains("send") || id.contains("send")) && node.isClickable
        }
        return send?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickFirstYouTubeResult(root: AccessibilityNodeInfo): Boolean {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, candidates)

        // Prefer a video-result-like clickable node. Skip navigation/search controls.
        val firstVideo = candidates.firstOrNull { node ->
            if (!node.isClickable) return@firstOrNull false
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = "$text $desc".lowercase()
            combined.isNotBlank() &&
                !combined.contains("search") &&
                !combined.contains("home") &&
                !combined.contains("shorts") &&
                !combined.contains("subscriptions") &&
                !combined.contains("library") &&
                (node.className?.toString()?.contains("TextView") == true || desc.isNotBlank())
        }
        return firstVideo?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out) }
        }
    }
}
