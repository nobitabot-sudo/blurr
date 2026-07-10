package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.blurr.voice.MyApplication
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.TextPart
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.blurr.voice.utilities.NetworkConnectivityManager
import com.blurr.voice.utilities.NetworkNotifier
import com.blurr.voice.utilities.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object GeminiApi {
    private val proxyUrl: String
        get() = ProxyConfig.getUrl(MyApplication.appContext)
    private val proxyKey: String
        get() = ProxyConfig.getKey(MyApplication.appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val db = Firebase.firestore


    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = "openrouter/free",
        maxRetry: Int = 4,
        context: Context? = null
    ): String? {
        try {
            val appCtx = context ?: MyApplication.appContext
            val isOnline = NetworkConnectivityManager(appCtx).isNetworkAvailable()
            if (!isOnline) {
                Log.e("GeminiApi", "No internet connection. Skipping generateContent call.")
                NetworkNotifier.notifyOffline()
                return null
            }
        } catch (e: Exception) {
            Log.e("GeminiApi", "Network check failed, assuming offline. ${e.message}")
            return null
        }

        if (proxyUrl.isBlank() || proxyKey.isBlank()) {
            Log.e("GeminiApi", "Proxy URL or Key is blank! Set them in Settings.")
            return null
        }

        val lastUserPrompt = chat.lastOrNull { it.first == "user" }
            ?.second
            ?.filterIsInstance<TextPart>()
            ?.joinToString(separator = "\n") { it.text } ?: "No text prompt found"

        var attempts = 0
        while (attempts < maxRetry) {
            Log.d("GeminiApi", "=== GEMINI API REQUEST (Attempt ${attempts + 1}) ===")
            Log.d("GeminiApi", "Model: $modelName")

            val attemptStartTime = System.currentTimeMillis()
            val payload = buildOpenAiPayload(chat, modelName)

            Log.d("GeminiApi", "Payload: ${payload.toString().take(500)}...")

            try {
                val endpoint = if (proxyUrl.endsWith("/chat/completions")) {
                    proxyUrl
                } else {
                    proxyUrl.trimEnd('/') + "/chat/completions"
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $proxyKey")
                    .build()

                val requestStartTime = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    val responseEndTime = System.currentTimeMillis()
                    val requestTime = responseEndTime - requestStartTime
                    val totalAttemptTime = responseEndTime - attemptStartTime
                    val responseBody = response.body?.string()

                    Log.d("GeminiApi", "=== GEMINI API RESPONSE (Attempt ${attempts + 1}) ===")
                    Log.d("GeminiApi", "HTTP Status: ${response.code}")
                    Log.d("GeminiApi", "Body: ${responseBody?.take(500)}")

                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        Log.e("GeminiApi", "API call failed with HTTP ${response.code}. Response: $responseBody")
                        throw Exception("API Error ${response.code}: $responseBody")
                    }

                    val parsedResponse = parseOpenAiResponse(responseBody)
                        ?: throw Exception("Failed to parse OpenAI-format response: $responseBody")

                    val logEntry = createLogEntry(
                        attempt = attempts + 1,
                        modelName = modelName,
                        prompt = lastUserPrompt,
                        imagesCount = images.size,
                        payload = payload.toString(),
                        responseCode = response.code,
                        responseBody = responseBody,
                        responseTime = requestTime,
                        totalTime = totalAttemptTime
                    )
                    saveLogToFile(MyApplication.appContext, logEntry)
                    val logData = createLogDataMap(
                        attempt = attempts + 1,
                        modelName = modelName,
                        prompt = lastUserPrompt,
                        imagesCount = images.size,
                        responseCode = null,
                        responseTime = requestTime,
                        totalTime = totalAttemptTime,
                        responseBody = responseBody,
                        status = "pass",
                    )
                    logToFirestore(logData)

                    return parsedResponse
                }
            } catch (e: Exception) {
                val attemptEndTime = System.currentTimeMillis()
                val totalAttemptTime = attemptEndTime - attemptStartTime

                Log.e("GeminiApi", "=== GEMINI API ERROR (Attempt ${attempts + 1}) ===", e)

                val logEntry = createLogEntry(
                    attempt = attempts + 1,
                    modelName = modelName,
                    prompt = lastUserPrompt,
                    imagesCount = images.size,
                    payload = payload.toString(),
                    responseCode = null,
                    responseBody = null,
                    responseTime = 0,
                    totalTime = totalAttemptTime,
                    error = e.message
                )
                saveLogToFile(MyApplication.appContext, logEntry)
                val logData = createLogDataMap(
                    attempt = attempts + 1,
                    modelName = modelName,
                    prompt = lastUserPrompt,
                    imagesCount = images.size,
                    responseCode = null,
                    responseTime = 0,
                    totalTime = totalAttemptTime,
                    status = "error",
                    responseBody = null,
                    error = e.message
                )
                logToFirestore(logData)

                attempts++
                if (attempts < maxRetry) {
                    val delayTime = 1000L * attempts
                    Log.d("GeminiApi", "Retrying in ${delayTime}ms...")
                    delay(delayTime)
                } else {
                    Log.e("GeminiApi", "Request failed after all ${maxRetry} retries.")
                    return null
                }
            }
        }
        return null
    }

    private fun buildOpenAiPayload(chat: List<Pair<String, List<Any>>>, modelName: String): JSONObject {
        val rootObject = JSONObject()
        rootObject.put("model", modelName)

        val messagesArray = JSONArray()
        chat.forEach { (role, parts) ->
            val textContent = parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            if (textContent.isNotBlank()) {
                val messageObject = JSONObject()
                val mappedRole = if (role.lowercase() == "model") "assistant" else "user"
                messageObject.put("role", mappedRole)
                messageObject.put("content", textContent)
                messagesArray.put(messageObject)
            }
            val hasImage = parts.any { it is ImagePart }
            if (hasImage) {
                Log.w("GeminiApi", "ImagePart found but skipped. The proxy payload format does not support images.")
            }
        }

        rootObject.put("messages", messagesArray)
        return rootObject
    }

    private fun parseOpenAiResponse(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                Log.e("GeminiApi", "Proxy/OpenRouter returned an error: ${json.getJSONObject("error")}")
                return null
            }
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w("GeminiApi", "No choices in response. Full response: $responseBody")
                return null
            }
            var content = choices.getJSONObject(0).getJSONObject("message").getString("content")
            content = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            content
        } catch (e: Exception) {
            Log.e("GeminiApi", "Failed to parse OpenAI-format response: $responseBody", e)
            null
        }
    }


    private fun saveLogToFile(context: Context, logEntry: String) {
        try {
            val logDir = File(context.filesDir, "gemini_logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, "gemini_api_log.txt")

            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
            Log.d("GeminiApi", "Log entry saved to: ${logFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("GeminiApi", "Failed to save log to file", e)
        }
    }
    private fun logToFirestore(logData: Map<String, Any?>) {
        val timestamp = System.currentTimeMillis()
        val promptSnippet = (logData["prompt"] as? String)?.take(40) ?: "log"

        val sanitizedPrompt = promptSnippet.replace(Regex("[^a-zA-Z0-9]"), "_")

        val documentId = "${timestamp}_$sanitizedPrompt"

        db.collection("gemini_logs")
            .document(documentId)
            .set(logData)
            .addOnSuccessListener {
                Log.d("GeminiApi", "Log sent to Firestore with ID: $documentId")
            }
            .addOnFailureListener { e ->
                Log.e("GeminiApi", "Error sending log to Firestore", e)
            }
    }
    private fun createLogEntry(
        attempt: Int,
        modelName: String,
        prompt: String,
        imagesCount: Int,
        payload: String,
        responseCode: Int?,
        responseBody: String?,
        responseTime: Long,
        totalTime: Long,
        error: String? = null
    ): String {
        return buildString {
            appendLine("=== GEMINI API DEBUG LOG ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            appendLine("Attempt: $attempt")
            appendLine("Model: $modelName")
            appendLine("Images count: $imagesCount")
            appendLine("Prompt length: ${prompt.length}")
            appendLine("Prompt: $prompt")
            appendLine("Payload: $payload")
            appendLine("Response code: $responseCode")
            appendLine("Response time: ${responseTime}ms")
            appendLine("Total time: ${totalTime}ms")
            if (error != null) {
                appendLine("Error: $error")
            } else {
                appendLine("Response body: $responseBody")
            }
            appendLine("=== END LOG ===")
        }
    }
    private fun createLogDataMap(
        attempt: Int,
        modelName: String,
        prompt: String,
        imagesCount: Int,
        responseCode: Int?,
        responseTime: Long,
        totalTime: Long,
        status: String,
        responseBody: String?,
        error: String? = null
    ): Map<String, Any?> {
        return mapOf(
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to status,
            "attempt" to attempt,
            "model" to modelName,
            "prompt" to prompt,
            "imagesCount" to imagesCount,
            "responseCode" to responseCode,
            "responseTimeMs" to responseTime,
            "totalTimeMs" to totalTime,
            "llmReply" to responseBody,
            "error" to error
        )
    }
}
