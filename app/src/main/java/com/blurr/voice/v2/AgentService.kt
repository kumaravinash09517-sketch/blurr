package com.blurr.voice.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.blurr.voice.R
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.api.Eyes
import com.blurr.voice.api.Finger
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.utilities.VisualFeedbackManager
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.v2.actions.ActionExecutor
import com.blurr.voice.v2.fs.FileSystem
import com.blurr.voice.v2.llm.GeminiApi
import com.blurr.voice.v2.message_manager.MemoryManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A Foreground Service responsible for hosting and running the AI Agent.
 *
 * This service manages the entire lifecycle of the agent, from initializing its components
 * to running its main loop in a background coroutine. It starts as a foreground service
 * to ensure the OS does not kill it while it's performing a long-running task.
 */
class AgentService : Service() {

    private val TAG = "AgentService"

    // A dedicated coroutine scope tied to the service's lifecycle.
    // Using a SupervisorJob ensures that if one child coroutine fails, it doesn't cancel the whole scope.
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val visualFeedbackManager by lazy { VisualFeedbackManager.getInstance(this) }

    // Declare agent and its dependencies. They will be initialized in onCreate.
    private val taskQueue: Queue<String> = ConcurrentLinkedQueue()
    private lateinit var agent: Agent
    private lateinit var settings: AgentSettings
    private lateinit var fileSystem: FileSystem
    private lateinit var memoryManager: MemoryManager
    private lateinit var perception: Perception
    private lateinit var llmApi: GeminiApi
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var overlayManager: OverlayManager

    // Firebase instances for task tracking
    private val db = Firebase.firestore
    private val auth = Firebase.auth

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "AgentServiceChannelV2"
        private const val NOTIFICATION_ID = 14
        private const val EXTRA_TASK = "com.blurr.voice.v2.EXTRA_TASK"
        private const val ACTION_STOP_SERVICE = "com.blurr.voice.v2.ACTION_STOP_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentTask: String? = null
            private set

        /**
         * A public method to request the service to stop from outside.
         */
        fun stop(context: Context) {
            Log.d("AgentService", "External stop request received.")
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }

        fun start(context: Context, task: String) {
            Log.d("AgentService", "Starting service with task: $task")
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_TASK, task)
            }
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.blurr.voice.utilities.FreemiumManager().decrementTaskCount()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            context.startService(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        overlayManager = OverlayManager.getInstance(this)
        OverlayDispatcher.clearAll()
        overlayManager.startObserving()

        visualFeedbackManager.showTtsWave()
        createNotificationChannel()

        settings = AgentSettings() // Use default settings for now
        fileSystem = FileSystem(this)
        memoryManager = MemoryManager(this, "", fileSystem, settings)
        perception = Perception(Eyes(this), SemanticParser())
        llmApi = GeminiApi(
            "gemini-2.5-flash",
            apiKeyManager = ApiKeyManager,
            context = this,
            maxRetry = 10
        )
        actionExecutor = ActionExecutor(Finger(this))
        agent = Agent(
            settings,
            memoryManager,
            perception,
            llmApi,
            actionExecutor,
            fileSystem,
            this
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received.")

        // Handle stop action
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Received stop action. Stopping service.")
            stopSelf() // onDestroy will handle cleanup
            return START_NOT_STICKY
        }

        // Add new task to the queue
        intent?.getStringExtra(EXTRA_TASK)?.let {
            if (it.isNotBlank()) {
                Log.d(TAG, "Adding task to queue: $it")
                taskQueue.add(it)
            }
        }

        // If the agent is not already processing tasks, start the loop.
        if (!isRunning && taskQueue.isNotEmpty()) {
            Log.i(TAG, "Agent not running, starting processing loop.")
            serviceScope.launch {
                processTaskQueue()
            }
        } else {
            if(isRunning) Log.d(TAG, "Task added to queue. Processor is already running.")
            else Log.d(TAG, "Service started with no task, waiting for tasks.")
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun processTaskQueue() {
        if (isRunning) {
            Log.d(TAG, "processTaskQueue called but already running.")
            return
        }
        isRunning = true

        Log.i(TAG, "Starting task processing loop.")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        startForeground(NOTIFICATION_ID, createNotification("Agent is starting..."))

        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.poll() ?: continue // Dequeue task, continue if null
            currentTask = task

            // Update notification for the new task
            notificationManager.notify(NOTIFICATION_ID, createNotification("Agent is running task: $task"))

            try {
                Log.i(TAG, "Executing task: $task")
                trackTaskInFirebase(task)
                agent.run(task)
                trackTaskCompletion(task, true)
                Log.i(TAG, "Task completed successfully: $task")
            } catch (e: Exception) {
                Log.e(TAG, "Task failed with an exception: $task", e)
                trackTaskCompletion(task, false, e.message)
            }
        }

        Log.i(TAG, "Task queue is empty. Stopping service.")
        isRunning = false
        stopSelf() // Stop the service only when the queue is empty
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service is being destroyed.")
        OverlayDispatcher.clearAll()
        overlayManager.stopObserving()
        isRunning = false
        currentTask = null
        taskQueue.clear()
        serviceScope.cancel()
        visualFeedbackManager.hideTtsWave()
        Log.i(TAG, "Service destroyed and all resources cleaned up.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Panda Doing Task (Expand to stop Panda)")
            .setContentText(contentText)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Panda",
                stopPendingIntent
            )
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private suspend fun trackTaskInFirebase(task: String) {
        try {
            val currentUser = auth.currentUser ?: return
            val taskEntry = hashMapOf(
                "task" to task,
                "status" to "started",
                "startedAt" to Timestamp.now(),
                "completedAt" to null,
                "success" to null,
                "errorMessage" to null
            )
            db.collection("users").document(currentUser.uid)
                .update("taskHistory", FieldValue.arrayUnion(taskEntry))
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Non-blocking Firebase track error: ${e.message}")
        }
    }

    private suspend fun trackTaskCompletion(task: String, success: Boolean, errorMessage: String? = null) {
        try {
            val currentUser = auth.currentUser ?: return
            val completionEntry = hashMapOf(
                "task" to task,
                "status" to if (success) "completed" else "failed",
                "completedAt" to Timestamp.now(),
                "success" to success,
                "errorMessage" to errorMessage
            )
            db.collection("users").document(currentUser.uid)
                .update("taskHistory", FieldValue.arrayUnion(completionEntry))
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Non-blocking Firebase track completion error: ${e.message}")
        }
    }
}
