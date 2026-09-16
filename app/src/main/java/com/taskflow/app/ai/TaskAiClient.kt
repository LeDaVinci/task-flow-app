package com.taskflow.app.ai

import com.taskflow.app.BuildConfig
import com.taskflow.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TaskAiClient(
    private val apiKey: String = BuildConfig.TASK_API_KEY,
    private val baseUrl: String = BuildConfig.TASK_API_BASE_URL,
    private val model: String = BuildConfig.TASK_API_MODEL,
) {
    suspend fun complete(system: String, prompt: String, temperature: Double = 0.3): String = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) { "AI 配置不完整" }
        val payload = JSONObject().put("model", model).put("temperature", temperature)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val connection = URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            AppLog.i("TaskAI", "response: HTTP $code")
            check(code in 200..299) { "AI 请求失败（$code）" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { connection.disconnect() }
    }
}
