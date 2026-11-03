package com.openobd.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One turn of the assistant conversation. */
data class ChatTurn(val fromUser: Boolean, val text: String)

class AssistantException(message: String) : Exception(message)

/** Something that answers a conversation; the Claude implementation is below, tests use a fake. */
fun interface ChatBackend {
    suspend fun reply(system: String, turns: List<ChatTurn>): String
}

/**
 * Optional AI help for a diagnosis, using the user's own Claude API key. Only the car data in the context
 * message is sent (codes, values, model) - never the VIN or location.
 */
class ClaudeBackend(apiKey: String, private val model: String = MODEL) : ChatBackend {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun reply(system: String, turns: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            // Chat-style answers: medium effort (Claude Opus 5.5's default, set explicitly).
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // If a safety classifier declines, let the API retry on a suitable model instead of stopping.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .apply { turns.forEach { if (it.fromUser) addUserMessage(it.text) else addAssistantMessage(it.text) } }
            .build()
        val response = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw AssistantException("The Claude API key was rejected. Check it in Settings.")
        } catch (e: PermissionDeniedException) {
            throw AssistantException("This API key is not allowed to use the model.")
        } catch (e: RateLimitException) {
            throw AssistantException("Too many requests right now. Wait a minute and try again.")
        } catch (e: AnthropicServiceException) {
            throw AssistantException("The assistant is not available right now. Try again later.")
        } catch (e: AnthropicIoException) {
            throw AssistantException("No connection to the assistant. Check your internet connection.")
        }
        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw AssistantException("The assistant declined to answer this request.")
        }
        response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("\n").trim()
            .ifEmpty { throw AssistantException("The assistant returned an empty answer.") }
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}

/** Builds the prompt and keeps the conversation for one diagnosis. */
class DiagnosisAssistant(private val backend: ChatBackend, language: String) {
    val system = """
        You are the diagnostic assistant inside OpenOBD, an OBD-II car diagnostics app. You are talking to the car's owner,
        who may not be a mechanic. The first message contains the data the app read from the car; treat it as the facts and
        never invent readings that are not there. Answer in ${if (language == "es") "Spanish (Spain)" else "the user's language"}.
        Be practical and short: the most likely causes for this car and data, in order, what to check first and how, whether it
        is safe to keep driving, and when a mechanic is needed. Mention typical repair cost ranges in euros only if asked.
        If something involves brakes, steering, airbags, overheating, oil pressure or fuel leaks, say clearly to stop and get help.
        Do not suggest procedures that need dealer tools unless you say so.
    """.trimIndent().replace("\n", " ")

    private val turns = mutableListOf<ChatTurn>()
    val conversation: List<ChatTurn> get() = turns.drop(1) // the first turn is the data context

    /** Starts the conversation with the car data and returns the first answer. */
    suspend fun start(context: String, question: String): String {
        turns.clear()
        turns += ChatTurn(true, context + "\n\n" + question)
        return answer()
    }

    suspend fun ask(question: String): String {
        turns += ChatTurn(true, question)
        return answer()
    }

    private suspend fun answer(): String {
        val reply = try {
            backend.reply(system, turns.toList())
        } catch (e: Exception) {
            turns.removeAt(turns.lastIndex) // keep the history consistent so the user can retry
            throw e
        }
        turns += ChatTurn(false, reply)
        return reply
    }
}
