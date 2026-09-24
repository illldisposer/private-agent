package com.orailnoor.privateagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AgentAccessibilityService : AccessibilityService() {

    private val ownPackageName = "com.orailnoor.privateagent"

    // OPT-3: Verhindert parallele Screenshot-Aufrufe (sonst Queue-Stau)
    private val screenshotInProgress = AtomicBoolean(false)

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
        var eventListener: ((Map<String, Any>) -> Unit)? = null
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val listener = eventListener ?: return
        if (event.packageName?.toString() == ownPackageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                var text = node?.text?.toString()
                    ?: node?.contentDescription?.toString() ?: ""
                if (text.isEmpty() && event.text.isNotEmpty()) {
                    text = event.text.joinToString(" ")
                }
                val rect = Rect()
                node?.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                if (text.isNotEmpty() || cx != 0 || cy != 0) {
                    listener(mapOf("type" to "click", "text" to text, "x" to cx, "y" to cy))
                }
                node?.recycle()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                listener(mapOf("type" to "scroll"))
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ─── Screen Reading ───────────────────────────────────────────────────

    /**
     * Dump the current screen as a flat list of UI elements.
     *
     * OPT-1: Iterative BFS statt Rekursion — kein StackOverflow bei tiefen
     * WebView-/RecyclerView-Hierarchien (50+ Ebenen).
     */
    fun dumpScreen(): List<Map<String, Any?>> {
        val nodes = mutableListOf<Map<String, Any?>>()

        // windows einmal cachen — jeder Zugriff ist ein Binder-IPC
        val wins = windows
        val roots = mutableListOf<AccessibilityNodeInfo>()

        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != ownPackageName) roots.add(root)
            else root.recycle()
        } else {
            for (w in wins) {
                val root = w.root ?: continue
                if (root.packageName?.toString() == ownPackageName) { root.recycle(); continue }
                roots.add(root)
            }
        }

        for (root in roots) {
            collectNodes(root, nodes)
            root.recycle()
        }
        return nodes
    }

    /**
     * OPT-1: Iterative BFS — ersetzt die rekursive traverseNode().
     * ArrayDeque<Pair> vermeidet Wrapper-Allokationen.
     */
    private fun collectNodes(root: AccessibilityNodeInfo, out: MutableList<Map<String, Any?>>) {
        // Stack: (node, depth) — DFS erhält Dokumentreihenfolge wie vorher
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            if (visible && (text.isNotEmpty() || contentDesc.isNotEmpty() ||
                        node.isClickable || node.isEditable || node.isScrollable)) {
                out.add(
                    mapOf(
                        "index"              to out.size,
                        "text"               to text,
                        "contentDescription" to contentDesc,
                        "className"          to (node.className?.toString() ?: "").substringAfterLast('.'),
                        "viewId"             to (node.viewIdResourceName ?: ""),
                        "isClickable"        to node.isClickable,
                        "isEditable"         to node.isEditable,
                        "isScrollable"       to node.isScrollable,
                        "isCheckable"        to node.isCheckable,
                        "isChecked"          to node.isChecked,
                        "isFocused"          to node.isFocused,
                        "bounds"             to mapOf(
                            "left"   to rect.left,  "top"    to rect.top,
                            "right"  to rect.right, "bottom" to rect.bottom
                        ),
                        "depth" to depth
                    )
                )
            }

            // Kinder in umgekehrter Reihenfolge pushen → DFS-Reihenfolge bleibt korrekt
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child to depth + 1)
            }

            // Root nicht hier recyceln — Caller macht das
            if (node !== root) node.recycle()
        }
    }

    /**
     * Screenshot als Base64-JPEG.
     *
     * OPT-3: AtomicBoolean verhindert parallele Aufrufe.
     * OPT-6: scaleFactor reduziert Payload — 0.5 = 25% der Originalgröße,
     *         für Claude Vision vollkommen ausreichend.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshot(
        scaleFactor: Float = 0.5f,
        quality: Int = 70,
        callback: (String?) -> Unit
    ) {
        if (!screenshotInProgress.compareAndSet(false, true)) {
            // Concurrent call — verwerfen statt queuen
            callback(null)
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = result.hardwareBuffer
                    val full = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()

                    if (full == null) { callback(null); return }

                    val scaled = if (scaleFactor < 1f && scaleFactor > 0f) {
                        Bitmap.createScaledBitmap(
                            full,
                            (full.width  * scaleFactor).toInt().coerceAtLeast(1),
                            (full.height * scaleFactor).toInt().coerceAtLeast(1),
                            true
                        ).also { if (it !== full) full.recycle() }
                    } else full

                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                    if (scaled !== full) scaled.recycle() else full.recycle()

                    callback(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                } finally {
                    screenshotInProgress.set(false)
                }
            }

            override fun onFailure(errorCode: Int) {
                screenshotInProgress.set(false)
                callback(null)
            }
        })
    }

    // ─── Actions ─────────────────────────────────────────────────────────

    /**
     * OPT-2: Single-Pass mit Scoring statt 4 separater Baumtraversierungen.
     * Priorität: exakt+nicht-editierbar > exakt > contains+nicht-editierbar > contains.
     */
    fun clickByText(targetText: String): Boolean {
        val wins = windows ?: return false
        for (window in wins) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) { root.recycle(); continue }
            val clicked = clickBestMatch(root, targetText)
            root.recycle()
            if (clicked) return true
        }
        return false
    }

    private fun clickBestMatch(root: AccessibilityNodeInfo, target: String): Boolean {
        data class Candidate(val node: AccessibilityNodeInfo, val score: Int)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var best: Candidate? = null

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val exact    = text.equals(target, true) || desc.equals(target, true)
            val contains = text.contains(target, true) || desc.contains(target, true)

            if (exact || contains) {
                val score = when {
                    exact    && !node.isEditable -> 3
                    exact                        -> 2
                    contains && !node.isEditable -> 1
                    else                         -> 0
                }
                if (score > (best?.score ?: -1)) {
                    best?.node?.recycle()
                    best = Candidate(AccessibilityNodeInfo.obtain(node), score)
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child)
            }
            if (node !== root) node.recycle()
        }

        return best?.let {
            val clicked = clickNodeOrParent(it.node)
            it.node.recycle()
            clicked
        } ?: false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true

        val rect = Rect()
        node.getBoundsInScreen(rect)
        return !rect.isEmpty && clickAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build(), null, null
        )
    }

    fun typeText(text: String, fieldHint: String? = null): Boolean {
        val wins = windows ?: return false
        for (window in wins) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) { root.recycle(); continue }

            val editNode = findEditableNode(root, fieldHint)
                ?: if (!fieldHint.isNullOrEmpty()) findEditableNode(root, null) else null

            if (editNode != null) {
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                root.recycle()
                return success
            }
            root.recycle()
        }
        return false
    }

    fun pressEnter(): Boolean {
        val wins = windows ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (window in wins) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == ownPackageName) { root.recycle(); continue }
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val submitted = focused?.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
                focused?.recycle()
                root.recycle()
                if (submitted) return true
            }
        }

        for (window in wins) {
            val root = window.root ?: continue
            val actionNode = findKeyboardActionNode(root)
            val submitted = actionNode != null && clickNodeOrParent(actionNode)
            actionNode?.recycle()
            root.recycle()
            if (submitted) return true
        }

        for (window in wins) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                return clickAtCoordinates(
                    bounds.right  - bounds.width()  * 0.10f,
                    bounds.bottom - bounds.height() * 0.14f
                )
            }
        }
        return false
    }

    fun scroll(direction: String, targetText: String? = null): Boolean {
        val wins = windows ?: return false
        for (window in wins) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == ownPackageName) { root.recycle(); continue }
            val scrollNode = findScrollableNode(root, targetText)
            if (scrollNode != null) {
                val action = when (direction.lowercase()) {
                    "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else             -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val success = scrollNode.performAction(action)
                root.recycle()
                return success
            }
            root.recycle()
        }
        return false
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(), null, null
        )
    }

    fun longPressAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
                .build(), null, null
        )
    }

    fun pressBack()          = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome()          = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents()        = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications()  = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun getCurrentPackage(): String? {
        val wins = windows ?: return null
        var fallback: String? = null
        for (window in wins) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            root.recycle()
            if (pkg == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (window.isActive || window.isFocused) return pkg
            if (pkg != ownPackageName && fallback == null) fallback = pkg
        }
        return fallback
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    /**
     * OPT-4: Bug-Fix — die ursprüngliche Version hatte `if (hint.isNullOrEmpty()) return node`
     * als toten Code (hint war an diesem Punkt garantiert nicht null).
     * Jetzt: hint==null → ersten editierbaren zurückgeben; hint gesetzt → Hint-Match prüfen,
     * ohne Match weitersuchen in Kindern.
     */
    private fun findEditableNode(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.isEditable) {
            if (hint.isNullOrEmpty()) return node
            val text     = node.text?.toString() ?: ""
            val desc     = node.contentDescription?.toString() ?: ""
            val hintText = node.hintText?.toString() ?: ""
            if (text.contains(hint, true) || desc.contains(hint, true) || hintText.contains(hint, true))
                return node
            // Kein Hint-Match → weiter in Kindern suchen (korrekt, kein früher Return)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child, hint)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo, targetText: String?): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            if (targetText == null) return node
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.contains(targetText, true) || desc.contains(targetText, true)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, targetText)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findKeyboardActionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (node.text?.toString().orEmpty().ifEmpty {
            node.contentDescription?.toString().orEmpty()
        }).trim().lowercase()
        val actionLabels = setOf("search", "enter", "go", "done", "send", "next")
        if (node.isClickable && (label in actionLabels || label.endsWith(" search"))) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findKeyboardActionNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
