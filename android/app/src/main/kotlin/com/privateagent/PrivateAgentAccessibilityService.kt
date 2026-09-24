package com.privateagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * PrivateAgent Accessibility Service
 * ───────────────────────────────────
 * Kommuniziert via MethodChannel mit dem Flutter-Layer:
 *   com.privateagent/accessibility  → Aktionen ausführen
 *   com.privateagent/screen         → Bildschirm erfassen & Bereitschaft prüfen
 */
class PrivateAgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PrivateAgentAccessibilityService? = null
        private const val ACTION_CHANNEL = "com.privateagent/accessibility"
        private const val SCREEN_CHANNEL = "com.privateagent/screen"
    }

    private val handler = Handler(Looper.getMainLooper())

    // Letztes Accessibility-Event (zum Erkennen von Ladebalken etc.)
    private var lastEvent: AccessibilityEvent? = null
    private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEvent     = event
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Kanal registrieren (wird von MainActivity aufgerufen) ──────────────

    fun registerChannels(engine: FlutterEngine) {
        // Aktionskanal
        MethodChannel(engine.dartExecutor.binaryMessenger, ACTION_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "tap"          -> { performTap(call.argument("x")!!, call.argument("y")!!); result.success(null) }
                    "swipe"        -> { performSwipe(call.argument("x1")!!, call.argument("y1")!!, call.argument("x2")!!, call.argument("y2")!!, call.argument("duration") ?: 300); result.success(null) }
                    "typeText"     -> { typeText(call.argument("text")!!); result.success(null) }
                    "openApp"      -> { openApp(call.argument("package")!!); result.success(null) }
                    "pressKey"     -> { pressGlobalAction(call.argument("keyCode")!!); result.success(null) }
                    "runTermuxCode"-> runTermuxCode(call.argument("code")!!, result)
                    else           -> result.notImplemented()
                }
            }

        // Bildschirmkanal
        MethodChannel(engine.dartExecutor.binaryMessenger, SCREEN_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "captureScreen" -> result.success(captureScreenAsMap())
                    "isScreenReady" -> result.success(isScreenReady())
                    else            -> result.notImplemented()
                }
            }
    }

    // ── TAP ───────────────────────────────────────────────────────────────

    private fun performTap(x: Double, y: Double) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    // ── SWIPE ─────────────────────────────────────────────────────────────

    private fun performSwipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Int) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path, 0L, durationMs.toLong()
        )
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )
    }

    // ── TEXT EINGEBEN ─────────────────────────────────────────────────────

    private fun typeText(text: String) {
        val focused = findFocusedInput(rootInActiveWindow) ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFocusedInput(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ── APP ÖFFNEN ────────────────────────────────────────────────────────

    private fun openApp(packageName: String) {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(intent)
    }

    // ── GLOBALE KEYS (HOME, BACK, RECENT) ─────────────────────────────────

    private fun pressGlobalAction(keyCode: Int) {
        val action = when (keyCode) {
            3   -> GLOBAL_ACTION_HOME
            4   -> GLOBAL_ACTION_BACK
            187 -> GLOBAL_ACTION_RECENTS
            else -> GLOBAL_ACTION_BACK
        }
        performGlobalAction(action)
    }

    // ── TERMUX CODE ───────────────────────────────────────────────────────

    private fun runTermuxCode(code: String, result: MethodChannel.Result) {
        // Erstellt eine Termux:Task-Intent oder nutzt Termux:API
        try {
            val intent = android.content.Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", code))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_TERMINAL", false)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startService(intent)
            handler.postDelayed({ result.success("Code gesendet an Termux") }, 500)
        } catch (e: Exception) {
            result.error("TERMUX_ERROR", e.message, null)
        }
    }

    // ── BILDSCHIRM ERFASSEN ───────────────────────────────────────────────

    fun captureScreenAsMap(): Map<String, Any?> {
        val root   = rootInActiveWindow ?: return mapOf("error" to "Kein Fenster")
        val result = mutableMapOf<String, Any?>()

        result["packageName"]  = root.packageName?.toString()
        result["windowTitle"]  = root.paneTitle?.toString()
        result["elements"]     = nodeToJson(root).toString()

        // Ladebalken erkennen
        result["hasLoadingBar"]     = detectLoadingBar(root)
        result["hasProgressBar"]    = detectProgressBar(root)
        result["hasDialog"]         = detectDialog(root)
        result["dialogText"]        = extractDialogText(root)

        return result
    }

    private fun nodeToJson(node: AccessibilityNodeInfo?, depth: Int = 0): JSONArray {
        val arr = JSONArray()
        if (node == null || depth > 8) return arr

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val obj   = JSONObject()

            val bounds = android.graphics.Rect()
            child.getBoundsInScreen(bounds)

            obj.put("text",        child.text?.toString() ?: "")
            obj.put("description", child.contentDescription?.toString() ?: "")
            obj.put("className",   child.className?.toString() ?: "")
            obj.put("clickable",   child.isClickable)
            obj.put("scrollable",  child.isScrollable)
            obj.put("editable",    child.isEditable)
            obj.put("checked",     child.isChecked)
            obj.put("enabled",     child.isEnabled)
            obj.put("x",     bounds.centerX())
            obj.put("y",     bounds.centerY())
            obj.put("left",  bounds.left)
            obj.put("top",   bounds.top)
            obj.put("right", bounds.right)
            obj.put("bottom",bounds.bottom)
            obj.put("children", nodeToJson(child, depth + 1))

            arr.put(obj)
            child.recycle()
        }
        return arr
    }

    // ── LADEBALKEN / DIALOG ERKENNUNG ─────────────────────────────────────

    private fun detectLoadingBar(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val cls = node.className?.toString() ?: ""
        if (cls.contains("ProgressBar") && node.isEnabled) return true
        for (i in 0 until node.childCount) {
            if (detectLoadingBar(node.getChild(i))) return true
        }
        return false
    }

    private fun detectProgressBar(node: AccessibilityNodeInfo?): Boolean =
        detectLoadingBar(node)

    private fun detectDialog(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val cls = node.className?.toString() ?: ""
        return cls.contains("Dialog") || cls.contains("AlertDialog")
    }

    private fun extractDialogText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        if (detectDialog(node)) {
            return collectText(node)
        }
        for (i in 0 until node.childCount) {
            val t = extractDialogText(node.getChild(i))
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    private fun collectText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" ")
        for (i in 0 until node.childCount) {
            sb.append(collectText(node.getChild(i)))
        }
        return sb.toString().trim()
    }

    // ── BILDSCHIRM BEREIT? ─────────────────────────────────────────────────

    fun isScreenReady(): Boolean {
        val root = rootInActiveWindow ?: return true
        // Nicht bereit wenn: Ladebalken aktiv ODER letztes Event < 300ms her
        val recentActivity = System.currentTimeMillis() - lastEventTime < 300
        val hasLoader      = detectLoadingBar(root)
        return !hasLoader && !recentActivity
    }
}
