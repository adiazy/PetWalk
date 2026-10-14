package com.petwalk.photosorter

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import java.io.Closeable

/** Rough costs assume a 768 px photo (~560 image tokens) and a short answer. */
enum class ClaudeModel(val id: String, val label: String, val note: String, val serverFallback: Boolean) {
    OPUS("claude-opus-5-5", "Opus 5.5", "Most accurate. Roughly $6 per 1,000 photos.", true),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "Very good. Roughly $3 per 1,000 photos.", true),
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "Good and cheapest. Roughly $0.15 per 1,000 photos.", false),
}

/** The API key was rejected or the account can't be billed, so there's no point continuing. */
class ClaudeAccountException(message: String) : Exception(message)

/** Sends a reduced copy of each photo to Claude and asks which of the user's categories fits. */
class ClaudeClassifier(
    apiKey: String,
    private val model: ClaudeModel,
    private val store: PhotoStore,
    private val labels: List<String>,
) : Closeable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .maxRetries(4)
        .build()

    private val system = buildString {
        appendLine("You sort personal photos into albums.")
        appendLine("Look at the photo and answer with exactly one category name from this list, copied exactly, and nothing else:")
        labels.forEach { appendLine("- $it") }
        appendLine("- ${Folders.OTHER}")
        append("Answer ${Folders.OTHER} only if none of the other categories fits.")
    }

    fun classify(photo: Photo): String {
        val jpeg = store.jpeg(photo.uri, MAX_SIDE) ?: return Folders.UNREADABLE
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build()
            )
            .build()
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(4096L)
            .system(system)
            // A quick visual judgment; low effort keeps it fast and cheap.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(TextBlockParam.builder().text("Which category does this photo belong to?").build()),
                )
            )
        if (model.serverFallback) {
            // If a safety classifier declines a photo, retry it on Anthropic's recommended fallback model.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val response = try {
            client.messages().create(params.build())
        } catch (e: AnthropicServiceException) {
            when (e.statusCode()) {
                401 -> throw ClaudeAccountException("Claude rejected the API key. Check it and try again.")
                402 -> throw ClaudeAccountException("Your Anthropic account needs credit. Add some at console.anthropic.com.")
                403 -> throw ClaudeAccountException("This API key isn't allowed to use ${model.label}.")
                else -> return Folders.NOT_CLASSIFIED
            }
        } catch (e: Exception) {
            return Folders.NOT_CLASSIFIED // network trouble after retries
        }

        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") return Folders.NOT_CLASSIFIED
        val answer = response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString(" ")
            .trim()
            .trim('.', '"', '\'', '*', ' ')
        val match = labels.firstOrNull { it.equals(answer, ignoreCase = true) }
            ?: labels.firstOrNull { answer.contains(it, ignoreCase = true) }
        return match?.let(Folders::sanitize) ?: Folders.OTHER
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_SIDE = 768
    }
}
