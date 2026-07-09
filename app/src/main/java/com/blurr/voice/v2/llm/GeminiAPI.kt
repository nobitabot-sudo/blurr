package com.blurr.voice.v2.llm

import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.v2.AgentOutput
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.blurr.voice.v2.logging.TaskLogger
import android.content.Context
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class GeminiApi(
    private val modelName: String,
    private val apiKeyManager: ApiKeyManager,
    private val context: Context,
    private val maxRetry: Int = 3
) {

    companion object {
        private const val TAG = "GeminiV2Api"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val proxyUrl: String
    get() = com.blurr.voice.utilities.ProxyConfig.getUrl(context)
private val proxyKey: String
    get() = com.blurr.voice.utilities.ProxyConfig.getKey(context)
    private val httpClient = OkHttpClient()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()

    private val jsonGenerationConfig = GenerationConfig.builder().apply {
        responseMimeType = "application/json"
    }.build()

    private val requestOptions = RequestOptions(timeout = 60.seconds)

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            Log.d(TAG, "Parsing guaranteed JSON response. $jsonString")
            Log.d("GEMINIAPITEMP_OUTPUT", jsonString)
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON into AgentOutput. Error: ${e.message}", e)
            null
        }
    }

    private suspend fun performApiCall(messages: List<GeminiMessage>): String {
        return if (!proxyUrl.isNullOrBlank() && !proxyKey.isNullOrBlank()) {
            Log.i(TAG, "Proxy config found. Using secure Cloud Function.")
            performProxyApiCall(messages)
        } else {
            Log.i(TAG, "Proxy config not found. Using direct Gemini SDK call (Fallback).")
            performDirectApiCall(messages)
        }
    }

    private suspend fun performProxyApiCall(messages: List<GeminiMessage>): String {
        val openAiMessages = messages.map { msg ->
            val combinedText = msg.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            val role = if (msg.role.name.lowercase() == "model") "assistant" else "user"
            OpenAIMessage(role = role, content = combinedText)
        }
        val requestPayload = OpenAIRequestBody(model = "auto", messages = openAiMessages)
        val jsonBody = jsonParser.encodeToString(OpenAIRequestBody.serializer(), requestPayload)

        val endpoint = if (proxyUrl.endsWith("/chat/completions")) {
            proxyUrl
        } else {
            proxyUrl.trimEnd('/') + "/chat/completions"
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $proxyKey")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBodyString = response.body?.string()
            if (!response.isSuccessful || responseBodyString.isNullOrBlank()) {
                val errorMsg = "Proxy API call failed with code: ${response.code}, body: $responseBodyString"
                Log.e(TAG, errorMsg)
                throw IOException(errorMsg)
            }
            var content = JSONObject(responseBodyString)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            content = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            Log.d(TAG, "Successfully received response from proxy: $content")
            return content
        }
    }

    private suspend fun performDirectApiCall(messages: List<GeminiMessage>): String {
        val apiKey = apiKeyManager.getNextKey()
        val generativeModel = modelCache.getOrPut(apiKey) {
            Log.d(TAG, "Creating new GenerativeModel instance for key ending in ...${apiKey.takeLast(4)}")
            GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = jsonGenerationConfig,
                requestOptions = requestOptions
            )
        }
        val history = convertToSdkHistory(messages)
        val response = generativeModel.generateContent(*history.toTypedArray())
        response.text?.let {
            Log.d(TAG, "Successfully received response from model.")
            return it
        }
        val reason = response.promptFeedback?.blockReason?.name ?: "UNKNOWN"
        throw ContentBlockedException("Blocked or empty response from API. Reason: $reason")
    }

    private fun convertToSdkHistory(messages: List<GeminiMessage>): List<Content> {
        return messages.map { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "tool"
            }

            content(role) {
                message.parts.forEach { part ->
                    if (part is TextPart) {
                        text(part.text)
                        if(part.text.startsWith("<agent_history>") || part.text.startsWith("Memory:")) {
                            Log.d("GEMINIAPITEMP_INPUT", part.text)
                        }
                    }
                }
            }
        }
    }

    suspend fun generateGroundedContent(prompt: String): String? {
        val apiKey = apiKeyManager.getNextKey()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"

        val jsonBody = """
        {
          "contents": [
            {
              "parts": [
                {"text": "$prompt"}
              ]
            }
          ],
          "tools": [
            {
              "google_search": {}
            }
          ]
        }
    """.trimIndent()

        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("x-goog-api-key", apiKey)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "Grounded API call failed with code: ${response.code}, body: $responseBody")
                return null
            }

            val text = JSONObject(responseBody)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            Log.d(TAG, "Successfully received grounded response.")
            text

        } catch (e: Exception) {
            Log.e(TAG, "Exception during grounded API call", e)
            null
        }
    }

}

@Serializable
private data class ProxyRequestPart(val text: String)

@Serializable
private data class ProxyRequestMessage(val role: String, val parts: List<ProxyRequestPart>)

@Serializable
private data class ProxyRequestBody(val modelName: String, val messages: List<ProxyRequestMessage>)

@Serializable
private data class OpenAIMessage(val role: String, val content: String)

@Serializable
private data class OpenAIRequestBody(val model: String, val messages: List<OpenAIMessage>, val temperature: Double = 0.7)

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1000L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}", e)
            if (attempt == times - 1) {
                Log.e("RetryUtil", "All $times retry attempts failed.")
                return null
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}
