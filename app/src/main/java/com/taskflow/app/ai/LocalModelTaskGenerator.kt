package com.taskflow.app.ai

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.taskflow.app.data.QuestBlueprint
import com.taskflow.app.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

enum class LocalModelAvailability {
    READY,
    MODEL_MISSING,
    ERROR,
}

data class LocalModelStatus(
    val availability: LocalModelAvailability,
    val message: String,
    val modelPath: String? = null,
)

data class LocalQuestDraft(
    val title: String,
    val description: String,
    val reward: String,
    val difficulty: String,
    val vibe: String,
)

sealed interface LocalGenerationResult {
    data class Success(
        val draft: LocalQuestDraft,
        val modelPath: String,
    ) : LocalGenerationResult

    data class Fallback(
        val status: LocalModelStatus,
    ) : LocalGenerationResult
}

class LocalModelTaskGenerator(
    private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelLock = Mutex()

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var llmInference: LlmInference? = null

    suspend fun setPreferredModelPath(path: String?): LocalModelStatus {
        val normalized = path?.trim().orEmpty().ifBlank { null }
        prefs.edit { putString(KEY_MODEL_PATH, normalized) }
        AppLog.i("LocalModel", "preferred model path updated: $normalized")
        closeLoadedModel()
        return checkAvailability()
    }

    suspend fun checkAvailability(): LocalModelStatus = withContext(Dispatchers.IO) {
        val modelFile = resolveModelFile()
            ?: return@withContext LocalModelStatus(
                availability = LocalModelAvailability.MODEL_MISSING,
                message = "未找到本地模型，请先推送 .task/.litertlm 到 /data/local/tmp/llm 或设置自定义路径",
            )

        knownUnsupportedReason(modelFile)?.let { reason ->
            AppLog.w("LocalModel", "skipping unsupported model: path=${modelFile.absolutePath}, reason=$reason")
            return@withContext LocalModelStatus(
                availability = LocalModelAvailability.ERROR,
                message = reason,
                modelPath = modelFile.absolutePath,
            )
        }

        try {
            ensureModel(modelFile)
            LocalModelStatus(
                availability = LocalModelAvailability.READY,
                message = "本地模型已就绪: ${modelFile.name}",
                modelPath = modelFile.absolutePath,
            ).also {
                AppLog.i("LocalModel", "model ready: path=${modelFile.absolutePath}")
            }
        } catch (t: Throwable) {
            AppLog.w("LocalModel", "availability check failed: path=${modelFile.absolutePath}", t)
            LocalModelStatus(
                availability = LocalModelAvailability.ERROR,
                message = "本地模型初始化失败: ${t.message ?: t.javaClass.simpleName}",
                modelPath = modelFile.absolutePath,
            )
        }
    }

    suspend fun generateQuest(
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String> = emptyList(),
    ): LocalGenerationResult = withContext(Dispatchers.IO) {
        val modelFile = resolveModelFile()
            ?: return@withContext LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.MODEL_MISSING,
                    message = "本地模型缺失，已回退模板任务",
                )
            )

        knownUnsupportedReason(modelFile)?.let { reason ->
            AppLog.w("LocalModel", "refusing unsupported model: path=${modelFile.absolutePath}, reason=$reason")
            return@withContext LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.ERROR,
                    message = reason,
                    modelPath = modelFile.absolutePath,
                )
            )
        }

        try {
            val model = ensureModel(modelFile)
            val prompts = listOf(
                buildPrompt(
                    vibe = vibe,
                    intensity = intensity,
                    durationMinutes = durationMinutes,
                    forceBoss = forceBoss,
                    avoidTitles = avoidTitles,
                ),
                buildCompactPrompt(
                    vibe = vibe,
                    intensity = intensity,
                    durationMinutes = durationMinutes,
                    forceBoss = forceBoss,
                    avoidTitles = avoidTitles,
                ),
                buildLabeledPrompt(
                    vibe = vibe,
                    intensity = intensity,
                    durationMinutes = durationMinutes,
                    forceBoss = forceBoss,
                    avoidTitles = avoidTitles,
                ),
            )

            var lastFailure = "empty response"
            var draft: LocalQuestDraft? = null
            for ((index, prompt) in prompts.withIndex()) {
                val attempt = index + 1
                AppLog.i("LocalModel", "prompt snapshot [attempt=$attempt]: $prompt")
                val response = model.generateResponse(prompt)
                val normalizedResponse = response.trim()
                AppLog.i("LocalModel", "raw model response [attempt=$attempt]: ${normalizedResponse.ifBlank { "<empty>" }}")
                val parsed = parseDraftOrNull(
                    raw = normalizedResponse,
                    vibe = vibe,
                    intensity = intensity,
                    durationMinutes = durationMinutes,
                    forceBoss = forceBoss,
                    avoidTitles = avoidTitles,
                )
                if (parsed != null) {
                    val sanitized = sanitizeDraft(
                        draft = parsed,
                        vibe = vibe,
                        intensity = intensity,
                        durationMinutes = durationMinutes,
                        forceBoss = forceBoss,
                        avoidTitles = avoidTitles,
                    )
                    draft = sanitized
                    AppLog.i("LocalModel", "parsed draft [attempt=$attempt]: title=${sanitized.title}, difficulty=${sanitized.difficulty}")
                    break
                }
                lastFailure = normalizedResponse.ifBlank { "empty response" }
            }

            require(draft != null) { "response could not be parsed: $lastFailure" }
            LocalGenerationResult.Success(
                draft = draft,
                modelPath = modelFile.absolutePath,
            )
        } catch (t: Throwable) {
            AppLog.w("LocalModel", "generation failed: path=${modelFile.absolutePath}", t)
            LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.ERROR,
                    message = "本地模型生成失败: ${t.message ?: t.javaClass.simpleName}",
                    modelPath = modelFile.absolutePath,
                )
            )
        }
    }

    suspend fun polishBlueprint(
        blueprint: QuestBlueprint,
        avoidTitles: List<String> = emptyList(),
    ): LocalGenerationResult = withContext(Dispatchers.IO) {
        if (isEmulatorRuntime()) {
            return@withContext LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.ERROR,
                    message = "模拟器上的 LiteRT 本地润色不稳定，已使用结构化任务模板",
                )
            )
        }

        val modelFile = resolveModelFile()
            ?: return@withContext LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.MODEL_MISSING,
                    message = "本地模型缺失，已使用结构化任务模板",
                )
            )

        knownUnsupportedReason(modelFile)?.let { reason ->
            AppLog.w("LocalModel", "refusing unsupported model: path=${modelFile.absolutePath}, reason=$reason")
            return@withContext LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.ERROR,
                    message = reason,
                    modelPath = modelFile.absolutePath,
                )
            )
        }

        try {
            val model = ensureModel(modelFile)
            val prompts = listOf(
                buildBlueprintPolishPrompt(blueprint, avoidTitles),
                buildBlueprintCompactPrompt(blueprint, avoidTitles),
            )
            var lastFailure = "empty response"
            var draft: LocalQuestDraft? = null
            for ((index, prompt) in prompts.withIndex()) {
                val attempt = index + 1
                AppLog.i("LocalModel", "blueprint prompt [attempt=$attempt]: $prompt")
                val response = model.generateResponse(prompt).trim()
                AppLog.i("LocalModel", "blueprint response [attempt=$attempt]: ${response.ifBlank { "<empty>" }}")
                val parsed = parseDraftOrNull(
                    raw = response,
                    vibe = blueprint.vibe,
                    intensity = blueprint.difficulty,
                    durationMinutes = blueprint.durationMinutes,
                    forceBoss = blueprint.difficulty == "boss",
                    avoidTitles = avoidTitles,
                )
                if (parsed != null) {
                    draft = sanitizeBlueprintDraft(parsed, blueprint, avoidTitles)
                    AppLog.i("LocalModel", "blueprint draft parsed [attempt=$attempt]: title=${draft.title}")
                    break
                }
                lastFailure = response.ifBlank { "empty response" }
            }

            require(draft != null) { "blueprint rewrite failed: $lastFailure" }
            LocalGenerationResult.Success(
                draft = draft,
                modelPath = modelFile.absolutePath,
            )
        } catch (t: Throwable) {
            AppLog.w("LocalModel", "blueprint polish failed: path=${modelFile.absolutePath}", t)
            LocalGenerationResult.Fallback(
                LocalModelStatus(
                    availability = LocalModelAvailability.ERROR,
                    message = "本地模型润色失败: ${t.message ?: t.javaClass.simpleName}",
                    modelPath = modelFile.absolutePath,
                )
            )
        }
    }

    private suspend fun ensureModel(modelFile: File): LlmInference = modelLock.withLock {
        val current = llmInference
        if (current != null && loadedModelPath == modelFile.absolutePath) {
            AppLog.i("LocalModel", "reusing loaded model: ${modelFile.absolutePath}")
            return@withLock current
        }

        current?.close()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .setMaxTopK(40)
            .build()

        return@withLock LlmInference.createFromOptions(context, options).also {
            loadedModelPath = modelFile.absolutePath
            llmInference = it
            AppLog.i("LocalModel", "model loaded: ${modelFile.absolutePath}")
        }
    }

    private suspend fun closeLoadedModel() = modelLock.withLock {
        llmInference?.close()
        llmInference = null
        loadedModelPath = null
    }

    private fun resolveModelFile(): File? {
        val preferred = prefs.getString(KEY_MODEL_PATH, null)?.trim().orEmpty()
        if (preferred.isNotBlank()) {
            val preferredFile = File(preferred)
            if (preferredFile.exists() && knownUnsupportedReason(preferredFile) == null) {
                return preferredFile
            }
            if (preferredFile.exists()) {
                AppLog.w("LocalModel", "preferred model is unsupported; scanning candidates: ${preferredFile.absolutePath}")
            }
        }

        candidateFiles()
            .firstOrNull { knownUnsupportedReason(it) == null }
            ?.let { return it }
        return null
    }

    private fun candidateFiles(): List<File> {
        val explicitCandidates = listOf(
            "/data/local/tmp/llm/gemma-3-1b-it-int4.task",
        ).map(::File)

        val directoryCandidates = buildList {
            add(File("/data/local/tmp/llm"))
            add(File(context.filesDir, "llm"))
            add(File(context.noBackupFilesDir, "llm"))
            context.getExternalFilesDir(null)?.let { add(File(it, "llm")) }
        }

        val scanned = directoryCandidates.flatMap { dir ->
            runCatching {
                dir.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".litertlm")) }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
            }.getOrElse { emptyList() }
        }

        return (explicitCandidates + scanned).distinctBy { it.absolutePath }.filter { it.exists() }
    }

    private fun buildPrompt(
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String>,
    ): String = """
        你是现实任务生成器。
        请基于输入生成一个有趣、具体、能马上执行的中文任务。

        输入:
        vibe=$vibe
        intensity=$intensity
        durationMinutes=$durationMinutes
        forceBoss=$forceBoss
        hiddenStyle=${pickStyleTag()}
        hiddenTwist=${pickTwistTag()}
        hiddenScene=${pickSceneTag()}
        hiddenObjective=${pickObjectiveTag()}
        hiddenNonce=${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}

        约束:
        只输出一行 JSON
        不要 markdown
        不要解释
        title 8到16个中文字符
        description 1句话
        reward 1句话
        difficulty 只能是 chill、spicy、chaotic、boss
        不要重复以前的标题
        不要输出“20分力”“震慑力十足”“Boss挑战”这种模板腔
        不要写成泛泛口号，要像真正能马上做的现实任务

        需要避开的旧标题:
        ${avoidTitles.ifEmpty { listOf("无") }.joinToString(" | ")}

        输出:
        {"title":"","description":"","reward":"","difficulty":"","vibe":""}
    """.trimIndent()

    private fun buildCompactPrompt(
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String>,
    ): String = """
        生成一个中文现实任务，只输出 JSON。
        参数: vibe=$vibe, intensity=$intensity, durationMinutes=$durationMinutes, forceBoss=$forceBoss, seed=${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}
        标题必须新，不要接近这些旧标题: ${avoidTitles.ifEmpty { listOf("无") }.joinToString(" | ")}
        禁止出现: 20分力, 震慑力十足, Boss挑战
        标题 8到16 个中文字，描述和奖励各一句，必须具体可执行。
        difficulty 只能是 chill、spicy、chaotic、boss。
        输出格式:
        {"title":"","description":"","reward":"","difficulty":"","vibe":""}
    """.trimIndent()

    private fun buildLabeledPrompt(
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String>,
    ): String = """
        生成一个中文现实任务。
        只输出下面 5 行，不能多字：
        title=...
        description=...
        reward=...
        difficulty=...
        vibe=...

        vibe=$vibe
        intensity=$intensity
        durationMinutes=$durationMinutes
        forceBoss=$forceBoss
        seed=${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}
        oldTitles=${avoidTitles.ifEmpty { listOf("无") }.joinToString(" | ")}
        banned=20分力,震慑力十足,Boss挑战
    """.trimIndent()

    private fun buildBlueprintPolishPrompt(
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
    ): String = """
        只做文案润色，不改任务逻辑。
        theme=${blueprint.theme.label}
        mode=${blueprint.mode.label}
        title=${blueprint.title}
        desc=${blueprint.description}
        reward=${blueprint.reward}
        output=${blueprint.outputHint}
        success=${blueprint.successSignal}
        duration=${blueprint.durationMinutes}
        vibe=${blueprint.vibe}
        difficulty=${blueprint.difficulty}
        avoid=${avoidTitles.take(3).ifEmpty { listOf("无") }.joinToString(" | ")}
        banned=地下建筑,战斗,魔法,世界观,20分力,震慑力十足,Boss挑战
        只输出一行 JSON:
        {"title":"","description":"","reward":"","difficulty":"${blueprint.difficulty}","vibe":"${blueprint.vibe}"}
    """.trimIndent()

    private fun buildBlueprintCompactPrompt(
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
    ): String = """
        只做中文润色，不改任务逻辑。
        已定蓝图:
        title=${blueprint.title}
        description=${blueprint.description}
        reward=${blueprint.reward}
        theme=${blueprint.theme.label}
        mode=${blueprint.mode.label}
        success=${blueprint.successSignal}
        banned=地下建筑,战斗,魔法,世界观,20分力,震慑力十足,Boss挑战
        avoid=${avoidTitles.take(3).ifEmpty { listOf("无") }.joinToString(" | ")}
        输出格式:
        title=...
        description=...
        reward=...
        difficulty=${blueprint.difficulty}
        vibe=${blueprint.vibe}
    """.trimIndent()

    private fun pickStyleTag(): String = listOf(
        "轻微恶作剧感",
        "都市潜行感",
        "桌面清理感",
        "社交突袭感",
        "自我训练感",
        "低成本冒险感",
    ).random()

    private fun pickTwistTag(): String = listOf(
        "加入随机元素",
        "加入计时压力",
        "加入一句记录动作",
        "加入一次移动身体",
        "加入一个清理动作",
        "加入一次对外输出",
    ).random()

    private fun pickSceneTag(): String = listOf(
        "工位",
        "卧室",
        "厨房",
        "阳台",
        "楼下",
        "聊天窗口",
    ).random()

    private fun pickObjectiveTag(): String = listOf(
        "降低拖延",
        "制造一点成就感",
        "打断无聊循环",
        "恢复一点秩序",
        "完成一个微型突破",
        "让今天有个记忆点",
    ).random()

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0..<end) { "response did not contain JSON: $raw" }
        return raw.substring(start, end + 1)
    }

    private fun extractJsonOrNull(raw: String): String? = runCatching {
        extractJson(raw)
    }.getOrNull()

    private fun parseDraftOrNull(
        raw: String,
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String>,
    ): LocalQuestDraft? {
        parseJsonDraftOrNull(raw, vibe, intensity)?.let { parsed ->
            if (parsed.title.isNotBlank()) return parsed
        }

        val title = parseLooseField(raw, "title")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val description = parseLooseField(raw, "description")
            ?: fallbackDescription(title, vibe, durationMinutes)
        val reward = parseLooseField(raw, "reward")
            ?: fallbackReward(intensity, durationMinutes, forceBoss)
        val difficulty = normalizeDifficultyValue(parseLooseField(raw, "difficulty"), intensity, forceBoss)
        val outputVibe = parseLooseField(raw, "vibe").orEmpty().ifBlank { vibe }
        return LocalQuestDraft(
            title = title,
            description = description,
            reward = reward,
            difficulty = difficulty,
            vibe = outputVibe,
        )
    }

    private fun parseJsonDraftOrNull(
        raw: String,
        vibe: String,
        intensity: String,
    ): LocalQuestDraft? {
        val jsonText = extractJsonOrNull(raw) ?: return null
        return runCatching {
            AppLog.i("LocalModel", "extracted JSON: $jsonText")
            val payload = JSONObject(jsonText)
            LocalQuestDraft(
                title = payload.getString("title"),
                description = payload.getString("description"),
                reward = payload.getString("reward"),
                difficulty = payload.optString("difficulty", intensity),
                vibe = payload.optString("vibe", vibe),
            )
        }.getOrNull()
    }

    private fun parseLooseField(
        raw: String,
        key: String,
    ): String? {
        val normalizedRaw = raw
            .replace("“", "\"")
            .replace("”", "\"")

        val quotedPattern = Regex(""""$key"\s*:\s*"([^"\n\r]+)"""")
        quotedPattern.find(normalizedRaw)?.groupValues?.getOrNull(1)?.let { return cleanGeneratedText(it) }

        val partialQuotedPattern = Regex(""""$key"\s*:\s*"([^,\n\r]+)""")
        partialQuotedPattern.find(normalizedRaw)?.groupValues?.getOrNull(1)?.let { return cleanGeneratedText(it) }

        val plainPattern = Regex("""(?im)^$key\s*[:=]\s*(.+)$""")
        plainPattern.find(normalizedRaw)?.groupValues?.getOrNull(1)?.let { return cleanGeneratedText(it) }

        return null
    }

    private fun cleanGeneratedText(value: String): String {
        return value
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("|", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('"', ',', '，')
    }

    private fun isAcceptableTitle(
        title: String,
        avoidTitles: List<String>,
    ): Boolean {
        if (title.length !in 4..24) return false
        if (title.any { it.isDigit() }) return false
        if (title.contains("-") || title.contains("_") || title.contains("\"")) return false
        if (title.contains("Boss挑战", ignoreCase = true)) return false
        if (title.contains("20分力")) return false
        if (title.contains("震慑力十足")) return false
        if (title.count { it.code in 0x4E00..0x9FFF } < 4) return false
        return avoidTitles.none { old -> title.contains(old) || old.contains(title) }
    }

    private fun fallbackDescription(
        title: String,
        vibe: String,
        durationMinutes: Int,
    ): String = when {
        vibe.contains("chaos") -> "围绕“$title”马上做一个 $durationMinutes 分钟的小动作，要求离开原位置并留下一个结果。"
        vibe.contains("chill") -> "围绕“$title”完成一个 $durationMinutes 分钟的轻量动作，结束后记下一句感受。"
        else -> "围绕“$title”做一个 $durationMinutes 分钟内能完成的现实动作，结束后留下一个可见结果。"
    }

    private fun fallbackReward(
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
    ): String = when {
        forceBoss -> "Boss 宝箱: ${50 + durationMinutes} XP"
        intensity == "chaotic" -> "混沌值 +${20 + durationMinutes}"
        intensity == "chill" -> "平静值 +${8 + durationMinutes}"
        else -> "行动值 +${12 + durationMinutes}"
    }

    private fun normalizeDifficultyValue(
        raw: String?,
        intensity: String,
        forceBoss: Boolean,
    ): String {
        if (forceBoss) return "boss"
        return when (raw?.trim()?.lowercase()) {
            "chill", "spicy", "chaotic", "boss" -> raw.trim().lowercase()
            else -> intensity
        }
    }

    private fun sanitizeBlueprintDraft(
        draft: LocalQuestDraft,
        blueprint: QuestBlueprint,
        avoidTitles: List<String>,
    ): LocalQuestDraft {
        val title = sanitizeBlueprintTitle(draft.title, blueprint.title, avoidTitles)
        val description = sanitizeBlueprintDescription(draft.description, blueprint.description)
        val reward = sanitizeBlueprintReward(draft.reward, blueprint.reward)
        return LocalQuestDraft(
            title = title,
            description = description,
            reward = reward,
            difficulty = blueprint.difficulty,
            vibe = blueprint.vibe,
        )
    }

    private fun sanitizeBlueprintTitle(
        raw: String,
        fallback: String,
        avoidTitles: List<String>,
    ): String {
        val cleaned = cleanGeneratedText(raw)
            .replace("混沌支线 ·", "")
            .replace("轻支线 ·", "")
            .replace("今日支线 ·", "")
            .trim()
        return if (isAcceptableTitle(cleaned, avoidTitles)) cleaned else fallback
    }

    private fun sanitizeBlueprintDescription(
        raw: String,
        fallback: String,
    ): String {
        val cleaned = cleanGeneratedText(raw)
        if (cleaned.length < 12) return fallback
        val bannedFragments = listOf("地下建筑", "战斗", "魔法", "世界", "卷入", "肾上腺", "震慑力十足")
        return if (bannedFragments.any { it in cleaned }) fallback else cleaned
    }

    private fun sanitizeBlueprintReward(
        raw: String,
        fallback: String,
    ): String {
        val cleaned = cleanGeneratedText(raw)
        if (cleaned.length < 4) return fallback
        return if (listOf("20分力", "25分力", "震慑力十足", "Boss挑战").any { it in cleaned }) fallback else cleaned
    }

    private fun sanitizeDraft(
        draft: LocalQuestDraft,
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String>,
    ): LocalQuestDraft {
        val title = sanitizeTitle(draft.title, vibe, avoidTitles)
        val description = sanitizeDescription(draft.description, title, vibe, durationMinutes)
        val reward = sanitizeReward(draft.reward, intensity, durationMinutes, forceBoss)
        return LocalQuestDraft(
            title = title,
            description = description,
            reward = reward,
            difficulty = normalizeDifficultyValue(draft.difficulty, intensity, forceBoss),
            vibe = vibe,
        )
    }

    private fun sanitizeTitle(
        raw: String,
        vibe: String,
        avoidTitles: List<String>,
    ): String {
        val cleaned = cleanGeneratedText(raw)
            .removePrefix("混沌支线 ·")
            .removePrefix("轻支线 ·")
            .removePrefix("今日支线 ·")
            .removePrefix("Boss ·")
            .replace("Boss热身", "")
            .replace("Boss 热身", "")
            .replace("欢迎来到", "")
            .replace("混沌支线板", "")
            .replace("电影预告", "")
            .trim()
        return if (isAcceptableTitle(cleaned, avoidTitles)) {
            cleaned
        } else {
            fallbackTitle(vibe)
        }
    }

    private fun sanitizeDescription(
        raw: String,
        title: String,
        vibe: String,
        durationMinutes: Int,
    ): String {
        val cleaned = cleanGeneratedText(raw)
        if (cleaned.length < 10) return fallbackDescription(title, vibe, durationMinutes)
        if ("准备好迎接" in cleaned || "无法控制" in cleaned || "肾上腺" in cleaned) {
            return fallbackDescription(title, vibe, durationMinutes)
        }
        return cleaned
    }

    private fun sanitizeReward(
        raw: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
    ): String {
        val cleaned = cleanGeneratedText(raw)
        if (cleaned.length < 4) return fallbackReward(intensity, durationMinutes, forceBoss)
        if ("20分力" in cleaned || "震慑力十足" in cleaned || "Boss挑战" in cleaned) {
            return fallbackReward(intensity, durationMinutes, forceBoss)
        }
        return cleaned
    }

    private fun fallbackTitle(vibe: String): String = when {
        vibe.contains("chaos") -> listOf(
            "楼道闪现清单",
            "桌面异动排查",
            "随机角落突击",
            "旧物翻找行动",
        ).random()
        vibe.contains("chill") -> listOf(
            "窗边缓冲任务",
            "桌面轻量归位",
            "安静角落记录",
            "低压整理回合",
        ).random()
        else -> listOf(
            "消息清零一条",
            "抽屉立即处理",
            "五分钟现实推进",
            "临时角落整顿",
        ).random()
    }

    private fun knownUnsupportedReason(modelFile: File): String? {
        val name = modelFile.name.lowercase()
        return when {
            "gemma-3n" in name || "e2b" in name ->
                "当前运行时不支持 Gemma 3n / E2B，本机请继续使用 Gemma3-1B-IT"
            else -> null
        }
    }

    private fun isEmulatorRuntime(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return "sdk_gphone" in fingerprint ||
            "generic" in fingerprint ||
            "emulator" in model
    }

    companion object {
        private const val PREFS_NAME = "local_model_prefs"
        private const val KEY_MODEL_PATH = "preferred_model_path"
    }
}
