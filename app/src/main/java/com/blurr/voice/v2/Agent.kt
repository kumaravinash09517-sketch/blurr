package com.blurr.voice.v2

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.blurr.voice.v2.actions.ActionExecutor
import com.blurr.voice.v2.fs.FileSystem
import com.blurr.voice.v2.llm.GeminiApi
import com.blurr.voice.v2.llm.GeminiMessage
import com.blurr.voice.v2.message_manager.MemoryManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.overlay.OverlayPriority
import com.blurr.voice.overlay.OverlayPosition
import com.blurr.voice.SettingsActivity
import kotlinx.coroutines.delay

/**
 * The main conductor of the agent.
 * This class owns all the necessary components and runs the primary SENSE -> THINK -> ACT loop.
 */
@RequiresApi(Build.VERSION_CODES.R)
class Agent(
    private val settings: AgentSettings,
    private val memoryManager: MemoryManager,
    private val perception: Perception,
    private val llmApi: GeminiApi,
    private val actionExecutor: ActionExecutor,
    private val fileSystem: FileSystem,
    private val context: Context
) {
    val state: AgentState = AgentState()
    private val TAG = "AgentV2"
    
    private val speechCoordinator = SpeechCoordinator.getInstance(context)
    val history: AgentHistoryList<Unit> = AgentHistoryList()

    suspend fun run(initialTask: String, maxSteps: Int = 150) {
        memoryManager.addNewTask(initialTask)
        state.stopped = false
        state.nSteps = 1
        Log.d(TAG, "--- Agent starting task: '$initialTask' ---")

        while (!state.stopped && state.nSteps <= maxSteps) {
            Log.d(TAG, "\n--- Step ${state.nSteps}/$maxSteps ---")

            // 1. SENSE: Observe the current state of the screen.
            Log.d(TAG, "👀 Sensing screen state...")
            val screenState = try {
                perception.analyze()
            } catch (e: Exception) {
                Log.e(TAG, "Screen perception failed, continuing without screen state", e)
                null
            }

            // 2. THINK (Prepare Prompt)
            Log.d(TAG, "🧠 Preparing prompt...")
            memoryManager.createStateMessage(
                modelOutput = state.lastModelOutput,
                result = state.lastResult,
                stepInfo = AgentStepInfo(state.nSteps, maxSteps),
                screenState = screenState
            )

            // 3. THINK (Get Decision)
            Log.d(TAG, "🤔 Asking LLM for next action...")
            val messages = memoryManager.getMessages()
            val agentOutput = llmApi.generateAgentOutput(messages)

            // --- Handle LLM Failure ---
            if (agentOutput == null) {
                Log.d(TAG, "❌ LLM failed to return a valid action. Retrying...")
                state.consecutiveFailures++
                memoryManager.addContextMessage(GeminiMessage(text = "System Note: Your previous output was not valid JSON. Please ensure your response is correctly formatted."))
                if (state.consecutiveFailures >= settings.maxFailures) {
                    Log.d(TAG, "❌ Agent failed too many times consecutively. Stopping.")
                    speechCoordinator.speakToUser("Agent failed after multiple attempts. Stopping execution.")
                    break
                }
                delay(1000)
                continue
            }

            state.consecutiveFailures = 0
            state.lastModelOutput = agentOutput
            Log.d(TAG, "🤖 LLM decided: ${agentOutput.nextGoal}")

            // Show thoughts if enabled
            val sharedPrefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
            if (sharedPrefs.getBoolean(SettingsActivity.KEY_SHOW_THOUGHTS, false)) {
                val thoughtText = buildString {
                    agentOutput.thinking?.let { if (it.isNotEmpty()) append("Thinking: $it\n") }
                    agentOutput.memory?.let { if (it.isNotEmpty()) append("Memory: $it\n") }
                    agentOutput.nextGoal?.let { if (it.isNotEmpty()) append("Next Goal: $it") }
                }.trim()

                if (thoughtText.isNotEmpty()) {
                    OverlayDispatcher.show(
                        text = thoughtText,
                        priority = OverlayPriority.TASKS,
                        duration = 8000L,
                        position = OverlayPosition.TOP
                    )
                }
            }

            // 4. ACT: Execute the LLM's planned actions.
            Log.d(TAG, "💪 Executing actions...")
            val actionResults = mutableListOf<ActionResult>()
            
            if (agentOutput.action.isEmpty()) {
                // If no UI action returned, treat as direct text response and complete task
                Log.d(TAG, "No UI actions, task completed with text response.")
                state.stopped = true
            } else {
                for (action in agentOutput.action) {
                    val result = actionExecutor.execute(action, screenState, context, fileSystem)
                    actionResults.add(result)
                    Log.d(TAG, "  - Action '${action::class.simpleName}' executed.")

                    if (result.isDone == true) {
                        state.stopped = true
                    }

                    if (result.error != null) {
                        Log.d(TAG, "  - 🛑 Action failed. Stopping current step's execution.")
                        break
                    }
                }
            }
            state.lastResult = actionResults

            // 5. RECORD
            history.addItem(
                AgentHistory(
                    modelOutput = agentOutput,
                    result = actionResults,
                    state = screenState,
                    metadata = null
                )
            )

            // --- Check for Task Completion ---
            if (state.stopped || actionResults.any { it.isDone == true }) {
                Log.d(TAG, "✅ Agent finished the task.")
                state.stopped = true
                break
            }

            state.nSteps++
            delay(1000)
        }

        // --- Loop Finished ---
        if (state.nSteps > maxSteps) {
            Log.d(TAG, "--- 🏁 Agent reached max steps. Stopping. ---")
            speechCoordinator.speakToUser("Agent reached maximum steps limit. Stopping execution.")
        } else {
            Log.d(TAG, "--- 🏁 Agent run finished successfully. ---")
        }
    }
}
