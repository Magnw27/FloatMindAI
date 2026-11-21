package id.floatmind.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class ProviderKind(val label: String) {
    OPENROUTER("OpenRouter"),
    GEMINI("Gemini API"),
    OPENAI_COMPATIBLE("9Router / OmniRoute / OpenAI-compatible")
}

data class AiConfig(
    val provider: ProviderKind,
    val apiKey: String,
    val model: String,
    val baseUrl: String = ""
)

data class ModelOption(val id: String, val free: Boolean)

object AiProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun listModels(config: AiConfig): List<ModelOption> {
        require(config.provider == ProviderKind.OPENAI_COMPATIBLE || config.apiKey.isNotBlank()) {
            "API key belum diisi."
        }
        val request = when (config.provider) {
            ProviderKind.OPENROUTER -> Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get().build()
            ProviderKind.GEMINI -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=${urlEncode(config.apiKey)}")
                .get().build()
            ProviderKind.OPENAI_COMPATIBLE -> Request.Builder()
                .url(openAiEndpoint(config.baseUrl, "models"))
                .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
                .get().build()
        }
        val json = execute(request)
        val models = when (config.provider) {
            ProviderKind.GEMINI -> {
                val array = json.optJSONArray("models") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val supportsChat = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    val name = item.optString("name").removePrefix("models/")
                    if (supportsChat && name.isNotBlank()) ModelOption(name, false) else null
                }
            }
            else -> {
                val array = json.optJSONArray("data") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    val pricing = item.optJSONObject("pricing")
                    val free = id.endsWith(":free", true) ||
                        (pricing != null && pricing.optString("prompt").toDoubleOrNull() == 0.0 && pricing.optString("completion").toDoubleOrNull() == 0.0)
                    ModelOption(id, free)
                }
            }
        }
        require(models.isNotEmpty()) { "Provider tidak mengembalikan model yang kompatibel." }
        return if (config.provider == ProviderKind.GEMINI) models.sortedBy { it.id }
        else models.sortedWith(compareByDescending<ModelOption> { it.free }.thenBy { it.id })
    }

    fun complete(config: AiConfig, system: String, prompt: String, maxTokens: Int = 900): String {
        require(config.model.isNotBlank()) { "Pilih model terlebih dahulu." }
        val request = when (config.provider) {
            ProviderKind.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens))
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${urlEncode(config.apiKey)}")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
            }
            else -> {
                val body = JSONObject()
                    .put("model", config.model)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("temperature", 0.2)
                    .put("max_tokens", maxTokens)
                Request.Builder()
                    .url(if (config.provider == ProviderKind.OPENROUTER)
                        "https://openrouter.ai/api/v1/chat/completions"
                    else openAiEndpoint(config.baseUrl, "chat/completions"))
                    .apply {
                        if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                        if (config.provider == ProviderKind.OPENROUTER) header("X-Title", "FloatMind AI")
                    }
                    .post(body.toString().toRequestBody(JSON))
                    .build()
            }
        }
        val response = execute(request)
        return when (config.provider) {
            ProviderKind.GEMINI -> response.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text").orEmpty()
            else -> response.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
        }.trim().ifBlank { throw IllegalStateException("Model mengembalikan jawaban kosong.") }
    }

    fun check(config: AiConfig): String {
        listModels(config)
        val answer = complete(
            config,
            "You are a connection diagnostic. Reply with exactly OK.",
            "Reply with exactly OK.",
            8
        )
        return if (answer.contains("OK", ignoreCase = true)) "Koneksi dan model berhasil diuji."
        else "Model merespons, tetapi hasil tes tidak sesuai: ${answer.take(100)}"
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) {
                throw IllegalStateException("Respons provider bukan JSON yang valid (HTTP ${response.code}).")
            }
            if (!response.isSuccessful) {
                val detail = json.optJSONObject("error")?.let {
                    it.optString("message").ifBlank { it.optString("status") }
                }.orEmpty()
                throw IllegalStateException("HTTP ${response.code}: ${detail.ifBlank { "Periksa key, endpoint, kuota, dan model." }.take(220)}")
            }
            return json
        }
    }

    private fun openAiEndpoint(base: String, path: String): String {
        val normalized = base.trim().trimEnd('/')
        require(normalized.startsWith("https://") ||
            normalized.startsWith("http://localhost") ||
            normalized.startsWith("http://127.0.0.1")) {
            "Endpoint harus HTTPS. HTTP hanya diizinkan untuk localhost."
        }
        require(normalized.isNotBlank()) { "Isi Base URL layanan OpenAI-compatible." }
        val root = if (normalized.endsWith("/v1")) normalized else "$normalized/v1"
        return "$root/$path"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private val JSON = "application/json; charset=utf-8".toMediaType()
}
