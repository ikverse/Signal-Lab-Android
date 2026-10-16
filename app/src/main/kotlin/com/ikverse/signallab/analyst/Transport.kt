package com.ikverse.signallab.analyst

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * How the analyst reaches Claude. The default route calls the Claude API with the owner's key; a later route (a PC host on
 * the owner's Claude Code login) implements the same contract.
 */
interface AnalystTransport {
    /** Sends [params] as one streamed request, handing each piece of text to [onText] as it arrives; returns the whole message. */
    suspend fun send(params: MessageCreateParams, onText: (String) -> Unit): Message
}

/** The Claude API through Anthropic's official Java SDK, on the owner's own API key. */
class ClaudeApiTransport(apiKey: String, baseUrl: String? = null) : AnalystTransport {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        // A long answer with web research can take minutes; the SDK retries rate limits and overloads itself.
        .timeout(Duration.ofMinutes(10))
        .maxRetries(2)
        .build()

    override suspend fun send(params: MessageCreateParams, onText: (String) -> Unit): Message = withContext(Dispatchers.IO) {
        val acc = MessageAccumulator.create()
        client.messages().createStreaming(params).use { response ->
            // Leaving the screen or pressing stop cancels the coroutine; closing the stream ends the request at once.
            val handle = coroutineContext[Job]?.invokeOnCompletion { runCatching { response.close() } }
            try {
                response.stream().forEach { event ->
                    acc.accumulate(event)
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onText(it.text()) }
                }
            } finally {
                handle?.dispose()
            }
        }
        acc.message()
    }
}
