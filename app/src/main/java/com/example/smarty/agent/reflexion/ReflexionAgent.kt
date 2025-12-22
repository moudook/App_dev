package com.example.smarty.agent.reflexion

import android.util.Log

/**
 * Reflexion Pattern implementation for self-correcting agent behavior.
 *
 * The Reflexion pattern (Shinn et al., 2023) enables agents to learn from mistakes
 * through a feedback loop:
 *
 * ```
 * Actor (generate) → Evaluator (assess) → Self-Reflection → Loop
 * ```
 *
 * Benefits:
 * - 30% improvement in complex task success rate
 * - Automatic error recovery without manual intervention
 * - Learning from mistakes within a single session
 *
 * @property maxRetries Maximum number of retry attempts (default: 2)
 * @property evaluator Function to evaluate response quality
 */
class ReflexionAgent<T>(
    private val maxRetries: Int = 2,
    private val evaluator: ResponseEvaluator = DefaultEvaluator()
) {
    companion object {
        private const val TAG = "ReflexionAgent"
    }

    /**
     * Execute with reflexion pattern - retry on poor quality responses.
     *
     * @param actor The function that generates responses
     * @param query Original user query
     * @return Final response after potential retries
     */
    suspend fun execute(
        query: String,
        actor: suspend (String) -> T
    ): ReflexionResult<T> {
        var currentQuery = query
        var lastResult: T? = null
        var lastEvaluation: Evaluation? = null
        val reflections = mutableListOf<String>()

        repeat(maxRetries + 1) { attempt ->
            Log.d(TAG, "Reflexion attempt ${attempt + 1}/${maxRetries + 1}")

            // Actor: Generate response
            val result = try {
                actor(currentQuery)
            } catch (e: Exception) {
                Log.e(TAG, "Actor failed: ${e.message}", e)
                // On exception, add reflection and retry
                val reflection = generateReflection(
                    Evaluation(
                        isAcceptable = false,
                        issue = "execution_error",
                        suggestion = "Previous attempt threw: ${e.message}. Try a different approach.",
                        confidence = 0f
                    )
                )
                reflections.add(reflection)
                currentQuery = "$query\n$reflection"
                return@repeat // Continue to next attempt
            }

            lastResult = result

            // Evaluator: Assess quality
            val evaluation = evaluator.evaluate(result)
            lastEvaluation = evaluation

            Log.d(TAG, "Evaluation: acceptable=${evaluation.isAcceptable}, issue=${evaluation.issue}, confidence=${evaluation.confidence}")

            // If acceptable, return immediately
            if (evaluation.isAcceptable) {
                return ReflexionResult(
                    response = result,
                    attempts = attempt + 1,
                    reflections = reflections,
                    finalEvaluation = evaluation
                )
            }

            // Self-Reflection: Generate guidance for next attempt
            val reflection = generateReflection(evaluation)
            reflections.add(reflection)

            // Augment query with reflection for next attempt
            currentQuery = "$query\n$reflection"
        }

        // Return last result even if not acceptable
        // FIX: Handle case where all attempts threw exceptions (lastResult could be null)
        Log.w(TAG, "Max retries reached, returning best available response")

        @Suppress("UNCHECKED_CAST")
        val finalResult = lastResult ?: run {
            // All attempts failed with exceptions - make one last attempt without reflection
            Log.e(TAG, "All attempts threw exceptions, making final attempt")
            try {
                actor(query)
            } catch (e: Exception) {
                Log.e(TAG, "Final attempt also failed: ${e.message}")
                throw IllegalStateException("All ${maxRetries + 1} attempts failed with exceptions", e)
            }
        }

        return ReflexionResult(
            response = finalResult,
            attempts = maxRetries + 1,
            reflections = reflections,
            finalEvaluation = lastEvaluation ?: Evaluation(false, "max_retries", "Exhausted retries", 0f)
        )
    }

    /**
     * Generate reflection text based on evaluation.
     */
    private fun generateReflection(evaluation: Evaluation): String {
        return buildString {
            append("[REFLECTION: Previous response was ${evaluation.issue}. ")
            append(evaluation.suggestion)
            append("]")
        }
    }

    /**
     * Response evaluation result.
     */
    data class Evaluation(
        val isAcceptable: Boolean,
        val issue: String,
        val suggestion: String,
        val confidence: Float // 0.0 to 1.0
    )

    /**
     * Result of reflexion execution.
     */
    data class ReflexionResult<T>(
        val response: T,
        val attempts: Int,
        val reflections: List<String>,
        val finalEvaluation: Evaluation
    )

    /**
     * Interface for response evaluation.
     */
    interface ResponseEvaluator {
        fun <T> evaluate(response: T): Evaluation
    }

    /**
     * Default evaluator that checks common response issues.
     */
    class DefaultEvaluator : ResponseEvaluator {
        override fun <T> evaluate(response: T): Evaluation {
            val text = response.toString()

            // Check for error indicators
            if (text.contains("error", ignoreCase = true) ||
                text.contains("failed", ignoreCase = true) ||
                text.contains("exception", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "error_detected",
                    suggestion = "The response contains error indicators. Please retry with better error handling.",
                    confidence = 0.3f
                )
            }

            // Check for empty or too short responses
            if (text.length < 10) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "too_brief",
                    suggestion = "The response is too short. Please provide a more complete answer.",
                    confidence = 0.2f
                )
            }

            // Check for uncertainty indicators
            if (text.contains("I don't know", ignoreCase = true) ||
                text.contains("not sure", ignoreCase = true) ||
                text.contains("cannot determine", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "uncertain",
                    suggestion = "The response expresses uncertainty. Try to find more specific information.",
                    confidence = 0.4f
                )
            }

            // Check for incomplete tool usage
            if (text.contains("tool_call", ignoreCase = true) &&
                !text.contains("result", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "incomplete_tool",
                    suggestion = "Tool was called but result not processed. Complete the tool execution.",
                    confidence = 0.5f
                )
            }

            // Default: acceptable
            return Evaluation(
                isAcceptable = true,
                issue = "none",
                suggestion = "",
                confidence = 0.9f
            )
        }
    }

    /**
     * Task-specific evaluator for note operations.
     */
    class NoteOperationEvaluator : ResponseEvaluator {
        override fun <T> evaluate(response: T): Evaluation {
            val text = response.toString()

            // Check if note operation succeeded
            if (text.contains("success:false", ignoreCase = true) ||
                text.contains("\"success\":false", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "operation_failed",
                    suggestion = "Note operation failed. Check if note exists and retry with correct ID.",
                    confidence = 0.3f
                )
            }

            // Check for missing note ID in update/delete operations
            if ((text.contains("update", ignoreCase = true) ||
                 text.contains("delete", ignoreCase = true)) &&
                !text.contains("noteId", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "missing_note_id",
                    suggestion = "Note ID is required. Search for the note first to get its ID.",
                    confidence = 0.4f
                )
            }

            // Default to base evaluator
            return DefaultEvaluator().evaluate(response)
        }
    }

    /**
     * Task-specific evaluator for search operations.
     */
    class SearchEvaluator : ResponseEvaluator {
        override fun <T> evaluate(response: T): Evaluation {
            val text = response.toString()

            // Check for empty results
            if (text.contains("totalCount:0", ignoreCase = true) ||
                text.contains("\"totalCount\":0", ignoreCase = true) ||
                text.contains("notes:[]", ignoreCase = true)) {
                return Evaluation(
                    isAcceptable = false,
                    issue = "no_results",
                    suggestion = "Search returned no results. Try broader search terms or different keywords.",
                    confidence = 0.5f
                )
            }

            return DefaultEvaluator().evaluate(response)
        }
    }
}
