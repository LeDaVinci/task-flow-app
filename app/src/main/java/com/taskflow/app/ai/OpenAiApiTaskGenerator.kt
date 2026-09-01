package com.taskflow.app.ai

import com.taskflow.app.BuildConfig
import com.taskflow.app.data.QuestBlueprint
import com.taskflow.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiQuestDraft(
    val title: String,
    val description: String,
    val reward: String,
)

sealed interface ApiGenerationResult {
    data class Success(val draft: ApiQuestDraft) : ApiGenerationResult
    data class Fallback(val message: String) : ApiGenerationResult
}

class OpenAiApiTaskGenerator(
    private val apiKey: String = BuildConfig.TASK_API_KEY,
    private val baseUrl: String = BuildConfig.TASK_API_BASE_URL,
    private val model: String = BuildConfig.TASK_API_MODEL,
) {

    suspend fun polishBlueprint(
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
        userTheme: String? = null,
    ): ApiGenerationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            AppLog.w("ApiTask", "generation skipped: API key is not configured")
            return@withContext ApiGenerationResult.Fallback("API 密钥未配置")
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            AppLog.w("ApiTask", "generation skipped: base URL or model is not configured")
            return@withContext ApiGenerationResult.Fallback("API 配置不完整")
        }

        try {
            AppLog.i("ApiTask", "generation started: model=$model, title=${blueprint.title}, duration=${blueprint.durationMinutes}")
            val content = requestCompletion(blueprint, avoidTitles, userTheme)
            val draft = parseDraft(content, blueprint, avoidTitles)
            if (draft == null) {
                AppLog.w("ApiTask", "generation fallback: response could not be parsed")
                return@withContext ApiGenerationResult.Fallback("API 返回内容无法解析")
            }
            AppLog.i("ApiTask", "generation succeeded: title=${draft.title}")
            ApiGenerationResult.Success(draft)
        } catch (error: Exception) {
            AppLog.w("ApiTask", "generation failed", error)
            ApiGenerationResult.Fallback("API 生成失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestCompletion(
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
        userTheme: String?,
    ): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.9)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "你是中文现实任务文案编辑。只返回 JSON，不要 markdown 或解释。")
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildPrompt(blueprint, avoidTitles, userTheme))
                    )
            )

        val connection = (URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

            val code = connection.responseCode
            AppLog.i("ApiTask", "response received: HTTP $code")
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${extractErrorMessage(body)}")
            }
            return JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPrompt(
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
        userTheme: String?,
    ): String = """
        基于以下固定任务蓝图，生成一条具体、轻量、可立刻执行的中文生活任务。
        theme=${blueprint.theme.label}
        mode=${blueprint.mode.label}
        difficulty=${blueprint.difficulty}
        durationMinutes=${blueprint.durationMinutes}
        title=${blueprint.title}
        description=${blueprint.description}
        reward=${blueprint.reward}
        avoidTitles=${avoidTitles.take(8).joinToString(" | ")}
        用户主题=${userTheme?.trim().takeUnless { it.isNullOrBlank() } ?: "随机生成"}
        约束：服务下班后的真实生活；15 到 30 分钟可完成；低风险、低成本、不需要专业工具或外部承诺。
        若用户主题不是“随机生成”，必须围绕该主题生成，不能改成无关任务。
        若用户主题是“随机生成”，任务范围限于生活整理、个人照料、轻量规划、轻社交、恢复行动或微型探索。
        输出格式: {"title":"","description":"","reward":""}
    """.trimIndent()

    private fun parseDraft(raw: String, blueprint: QuestBlueprint, avoidTitles: List<String>): ApiQuestDraft? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start !in 0..<end) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val title = json.optString("title").clean().takeIf { isUsableTitle(it, avoidTitles) } ?: blueprint.title
        val description = json.optString("description").clean().takeIf { it.length >= 12 } ?: blueprint.description
        val reward = json.optString("reward").clean().takeIf { it.length >= 4 } ?: blueprint.reward
        return ApiQuestDraft(title, description, reward)
    }

    private fun String.clean(): String = replace(Regex("\\s+"), " ").trim().trim('"', '，', ',')

    private fun isUsableTitle(title: String, avoidTitles: List<String>): Boolean =
        title.length in 4..28 &&
            title.count { it.code in 0x4E00..0x9FFF } >= 4 &&
            avoidTitles.none { old -> title.contains(old) || old.contains(title) }

    private fun extractErrorMessage(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull().orEmpty().ifBlank { body.take(240) }
}
