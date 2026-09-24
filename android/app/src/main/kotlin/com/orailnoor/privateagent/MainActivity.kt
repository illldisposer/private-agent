package com.orailnoor.privateagent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val EVENT_CHANNEL = "com.privateagent/accessibility_events"
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    AgentAccessibilityService.eventListener = { eventMap ->
                        runOnUiThread { eventSink?.success(eventMap) }
                    }
                }
                override fun onCancel(arguments: Any?) {
                    eventSink = null
                    AgentAccessibilityService.eventListener = null
                }
            })

        registerAccessibilityChannel(flutterEngine, this)
    }

    companion object {
        fun registerAccessibilityChannel(
            flutterEngine: FlutterEngine,
            context: android.content.Context
        ) {
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                "com.privateagent/accessibility"
            ).setMethodCallHandler { call, result ->
                android.util.Log.d("PrivateAgentKotlin", "Method: ${call.method}")

                // Helper: holt Service oder gibt Fehler zurück
                fun svc(): AgentAccessibilityService? =
                    AgentAccessibilityService.instance.also {
                        if (it == null) result.error("SERVICE_NOT_RUNNING",
                            "Accessibility service is not running", null)
                    }

                when (call.method) {

                    "ping" -> result.success(true)

                    "logToNative" -> {
                        android.util.Log.d("PrivateAgentDart",
                            call.argument<String>("message") ?: "")
                        result.success(true)
                    }

                    "isServiceRunning" ->
                        result.success(AgentAccessibilityService.isRunning())

                    "checkOverlayPermission" ->
                        result.success(Settings.canDrawOverlays(context))

                    "requestOverlayPermission" -> {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        result.success(true)
                    }

                    "openAccessibilitySettings" -> {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        result.success(true)
                    }

                    "showMacroOverlay" ->
                        result.error("NOT_SUPPORTED",
                            "Macro overlay not supported from background", null)

                    "hideMacroOverlay" -> result.success(true)

                    "showToast" -> {
                        android.widget.Toast.makeText(
                            context,
                            call.argument<String>("message") ?: "",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        result.success(true)
                    }

                    // ── Screen Reading ──────────────────────────────────

                    "dumpScreen" -> svc()?.let {
                        result.success(it.dumpScreen())
                    }

                    "takeScreenshot" -> svc()?.let { service ->
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                            result.error("UNSUPPORTED_VERSION",
                                "Screenshot requires Android 11+", null)
                            return@let
                        }
                        // OPT-6: scaleFactor + quality aus Flutter steuerbar
                        val scale   = call.argument<Double>("scaleFactor")?.toFloat() ?: 0.5f
                        val quality = call.argument<Int>("quality") ?: 70
                        service.takeScreenshot(scale, quality) { base64 ->
                            if (base64 != null) result.success(base64)
                            else result.error("SCREENSHOT_FAILED",
                                "Failed to capture screenshot", null)
                        }
                    }

                    // ── Actions ─────────────────────────────────────────

                    "clickByText" -> svc()?.let {
                        result.success(it.clickByText(call.argument<String>("text") ?: ""))
                    }

                    "clickAt" -> svc()?.let {
                        result.success(it.clickAtCoordinates(
                            call.argument<Double>("x")?.toFloat() ?: 0f,
                            call.argument<Double>("y")?.toFloat() ?: 0f
                        ))
                    }

                    "typeText" -> svc()?.let {
                        result.success(it.typeText(
                            call.argument<String>("text") ?: "",
                            call.argument<String>("fieldHint")
                        ))
                    }

                    "pressEnter" -> svc()?.let {
                        result.success(it.pressEnter())
                    }

                    "scroll" -> svc()?.let {
                        result.success(it.scroll(
                            call.argument<String>("direction") ?: "down",
                            call.argument<String>("target")
                        ))
                    }

                    "swipe" -> svc()?.let {
                        result.success(it.swipe(
                            call.argument<Double>("startX")?.toFloat() ?: 0f,
                            call.argument<Double>("startY")?.toFloat() ?: 0f,
                            call.argument<Double>("endX")?.toFloat()   ?: 0f,
                            call.argument<Double>("endY")?.toFloat()   ?: 0f
                        ))
                    }

                    // OPT-5: longPressAt war im Service vorhanden, aber nie im Channel verdrahtet
                    "longPressAt" -> svc()?.let {
                        result.success(it.longPressAt(
                            call.argument<Double>("x")?.toFloat() ?: 0f,
                            call.argument<Double>("y")?.toFloat() ?: 0f
                        ))
                    }

                    "pressBack"  -> svc()?.let { result.success(it.pressBack()) }
                    "pressHome"  -> svc()?.let { result.success(it.pressHome()) }

                    // OPT-5: openRecents war ebenfalls nicht verdrahtet
                    "openRecents" -> svc()?.let { result.success(it.openRecents()) }

                    "openNotifications" -> svc()?.let { result.success(it.openNotifications()) }

                    "getCurrentPackage" -> svc()?.let {
                        result.success(it.getCurrentPackage())
                    }

                    else -> result.notImplemented()
                }
            }
        }
    }
}

class BackgroundEngineReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val engine = io.flutter.embedding.engine.FlutterEngineCache
            .getInstance().get("myCachedEngine")
        if (engine == null) {
            android.util.Log.e("PrivateAgent", "Background engine myCachedEngine not found")
            return
        }
        android.util.Log.d("PrivateAgent",
            "Registering accessibility channel on myCachedEngine " +
            "(engine=${System.identityHashCode(engine)}, " +
            "dartExecuting=${engine.dartExecutor.isExecutingDart})")
        MainActivity.registerAccessibilityChannel(engine, context.applicationContext)
    }
}
