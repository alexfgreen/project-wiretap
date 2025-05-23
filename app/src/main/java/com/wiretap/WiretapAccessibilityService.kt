package com.wiretap

import ScreenshotManager
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.File
class WiretapAccessibilityService : AccessibilityService() {
    private val TAG = "WiretapAccessibility"

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var screenshotManager: ScreenshotManager

    private var textInputJob: Job? = null
    private val TEXT_INPUT_DELAY = 1000L
    private val TYPING_COOLDOWN = 2000L
    private val GESTURE_DELAY = 750L
    private var lastTypingTimestamp = 0L
    private var isTyping = false

    private var previousPackage: CharSequence? = null

    private var currentEpisodeDir: File? = null
    private var currentTreeIndex = 0

    private var hasReachedLauncher = false
    private var launcherVisitCount = 0
    private var isStartRequested = false

    private var lastActionTimestamp: Long = 0L

    private var episodeNumber = 0

    private sealed class Action {
        data class TextInput(val text: String) : Action()
        data class AppLaunch(val appName: String) : Action()
        object NavigateBack : Action()  // Use object instead of class
        data class Click(val x: Int, val y: Int) : Action()
        data class Swipe(
            val direction: String,
            val startX: Int,
            val startY: Int,
            val endX: Int,
            val endY: Int
        ) : Action()
    }

    private fun Action.toJson(): String = when (this) {
        is Action.TextInput -> """
            {
              "action_type": "input_text",
              "text": "$text"
            }""".trimIndent()

        is Action.AppLaunch -> """
            {
              "action_type": "open_app",
              "app_name": "$appName"
            }""".trimIndent()

        is Action.NavigateBack -> """
            {
              "action_type": "navigate_back"
            }""".trimIndent()

        is Action.Click -> """
            {
              "action_type": "click",
              "x": $x,
              "y": $y
            }""".trimIndent()

        is Action.Swipe -> """
            {
              "action_type": "scroll",
              "direction": "$direction"
            }""".trimIndent()
    }

    private var pendingGestureJob: Job? = null

    private fun isLauncher(packageName: String?): Boolean {
        return packageName?.contains("launcher", ignoreCase = true) == true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initializeNewEpisode() {
        isRecording = true
        hasReachedLauncher = true
        launcherVisitCount = 1

        val datasetDir = File(getExternalFilesDir(null), "wiretap_dataset")
        if (!datasetDir.exists()) {
            datasetDir.mkdirs()
        }
        episodeNumber = datasetDir.listFiles()?.size ?: 0
        currentEpisodeDir = File(datasetDir, "episode_$episodeNumber")
        currentEpisodeDir?.mkdirs()
        currentTreeIndex = 0

        screenshotManager.startPeriodicCapture()

        Log.d(TAG, "Created new episode directory: ${currentEpisodeDir?.absolutePath}")

        screenshotManager.saveCurrentScreenshotAndTrees(File(currentEpisodeDir?.absolutePath), currentTreeIndex)

        currentTreeIndex++

        lastActionTimestamp = System.currentTimeMillis()

        Log.d(TAG, "Reached launcher, captured initial state and started recording")
    }

    private fun saveMetadata() {
        currentEpisodeDir?.let { dir ->
            val actionsJson = recordingActions.joinToString(",\n")

            val metadata = """
{
  "episode_id": ${episodeNumber},
  "goal": ${currentGoal?.let { "\"$it\"" } ?: "null"},
  "actions": [
    $actionsJson
  ]
}""".trimIndent()

            File(dir, "metadata.json").writeText(metadata)
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenshotManager = ScreenshotManager(this, serviceScope)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE  // Add this flag
            notificationTimeout = 100
        }
        serviceInfo = info


        registerReceiver(
            recordingReceiver,
            IntentFilter().apply {
                addAction("com.wiretap.START_RECORDING")
                addAction("com.wiretap.STOP_RECORDING")
            }
        )

        // Register gesture receiver
        registerReceiver(
            gestureReceiver,
            IntentFilter().apply {
                addAction("com.wiretap.ACTION_GESTURE")
            }
        )

        Log.d(TAG, "Registered all receivers")

        Log.i(TAG, "WiretapAccessibilityService connected")
    }

    private var isRecording = false
    private var currentGoal: String? = null
    private val
            recordingActions = mutableListOf<String>()

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.wiretap.START_RECORDING" -> {
                    isStartRequested = true
                    currentGoal = intent.getStringExtra("goal")
                    recordingActions.clear()
                    // We'll initialize the episode when we actually start recording
                    Log.d(
                        TAG,
                        "Waiting for home screen before starting recording for goal: $currentGoal"
                    )
                }

                "com.wiretap.STOP_RECORDING" -> {
                    stopRecording()
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        isStartRequested = false
        hasReachedLauncher = false
        launcherVisitCount = 0
        saveMetadata()
        Log.d(TAG, "Recording completed and saved to ${currentEpisodeDir?.absolutePath}")
        currentEpisodeDir = null
        currentGoal = null
        recordingActions.clear()
        currentTreeIndex = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()

            if (isStartRequested && !isRecording && isLauncher(packageName)) {
                // First launcher visit - start recording
                initializeNewEpisode()
                return
            } else if (isRecording && isLauncher(packageName)) {
                // Second launcher visit - stop recording
                if (launcherVisitCount == 1) {
                    Log.d(TAG, "Returned to launcher, stopping recording")
                    stopRecording()
                    return
                }
            }
        }

        if (!isRecording) return

        val action = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                handleTextInput(text)
                return  // Early return to avoid immediate processing
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> when {
                event.packageName != null &&
                        event.className != null &&
                        !isLauncher(event.packageName.toString()) &&
                        event.className.toString().endsWith("Activity") &&
                        previousPackage != event.packageName -> {
                    previousPackage = event.packageName
                    Action.AppLaunch(event.packageName.toString())
                }

                event.contentDescription?.contains("back") == true ||
                        event.className?.contains("back") == true -> {
                    Action.NavigateBack
                }

                else -> null
            }

            else -> null
        }

        action?.let { handleAccessibilityAction(it) }
    }

    private suspend fun processAction(action: Action) {
        val actionJson = action.toJson()
        recordingActions.add(actionJson)

        screenshotManager.saveCurrentScreenshotAndTrees(File(currentEpisodeDir?.absolutePath), currentTreeIndex)
        lastActionTimestamp = System.currentTimeMillis()
        currentTreeIndex++
    }

    private fun handleAccessibilityAction(action: Action) {
        // Cancel any pending gesture since we got an accessibility event
        pendingGestureJob?.cancel()

        if (!isRecording) return

        serviceScope.launch {
            processAction(action)
        }
    }

    private fun handleTextInput(text: String) {
        val currentTime = System.currentTimeMillis()

        // Cancel any existing text input job
        textInputJob?.cancel()

        // Cancel any pending gesture jobs since we're now typing
        pendingGestureJob?.cancel()
        Log.d(TAG, "Cancelled pending gesture jobs due to text input")

        // Mark that we're in typing mode
        isTyping = true
        lastTypingTimestamp = currentTime

        // Debounce text input
        textInputJob = serviceScope.launch {
            delay(TEXT_INPUT_DELAY)

            // Process the text input action
            processAction(Action.TextInput(text))

            // Add cooldown period after typing
            delay(TYPING_COOLDOWN)
            isTyping = false
        }
    }

    private fun queueGestureAction(action: Action) {
        if (!isRecording) return

        pendingGestureJob?.cancel()
        pendingGestureJob = serviceScope.launch {
            // First delay to wait for potential accessibility events
            delay(GESTURE_DELAY)

            // Get timestamp after the delay
            val currentTime = System.currentTimeMillis()

            // Check if any accessibility events happened during or slightly before our delay
            // Add a small buffer before the delay to catch events that might be slightly out of sync
            if (lastActionTimestamp >= (currentTime - GESTURE_DELAY - 100)) {
                Log.d(TAG, "Discarding gesture as accessibility event occurred recently")
                return@launch
            }

            processAction(action)
        }
    }

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type")
            val x = intent?.getIntExtra("x", 0) ?: 0
            val y = intent?.getIntExtra("y", 0) ?: 0

            val action = when (type) {
                "CLICK" -> Action.Click(x, y)
                "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN" -> Action.Swipe(
                    direction = type,
                    startX = x,
                    startY = y,
                    endX = intent.getIntExtra("x2", -1),
                    endY = intent.getIntExtra("y2", -1)
                )
                else -> null
            }

            action?.let { queueGestureAction(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshotManager.stopPeriodicCapture()
        try {
            unregisterReceiver(gestureReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        serviceScope.cancel()
    }

    override fun onInterrupt() {
        Log.w(TAG, "WiretapAccessibilityService interrupted")
    }

}