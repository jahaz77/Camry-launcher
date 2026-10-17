package com.camry.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Asistente de voz con la API de Claude.
 * Las llamadas de red se hacen en un hilo aparte; las herramientas se ejecutan con [runTool].
 */
object Assistant {
    const val MODEL_FAST = "claude-haiku-5-5"
    const val MODEL_SMART = "claude-sonnet-5-5"

    private val history = JSONArray()
    private var lastUse = 0L

    fun apiKey(ctx: Context) = ThemeManager.prefs(ctx).getString("api_key", "")?.trim() ?: ""
    fun setApiKey(ctx: Context, k: String) = ThemeManager.prefs(ctx).edit().putString("api_key", k.trim()).apply()
    fun model(ctx: Context) = ThemeManager.prefs(ctx).getString("api_model", MODEL_FAST) ?: MODEL_FAST
    fun setModel(ctx: Context, m: String) = ThemeManager.prefs(ctx).edit().putString("api_model", m).apply()

    private fun tools(ctx: Context): JSONArray {
        val themes = ThemeManager.loadAll(ctx).joinToString { it.name }
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
            .put("name", name).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun enum(desc: String, vararg v: String) = str(desc).put("enum", JSONArray(v.toList()))
        return JSONArray()
            .put(tool("navegar", "Inicia la navegación en Google Maps. Usa 'casa' o 'trabajo' para las direcciones guardadas, o escribe el lugar o la dirección.",
                JSONObject().put("destino", str("Destino: 'casa', 'trabajo', un lugar o una dirección")), listOf("destino")))
            .put(tool("musica", "Controla la música que suena en el estéreo.",
                JSONObject().put("accion", enum("Acción", "reproducir", "pausar", "siguiente", "anterior")), listOf("accion")))
            .put(tool("abrir_app", "Abre una app del estéreo.",
                JSONObject().put("app", enum("App", "carplay", "mapas", "musica", "telefono", "radio", "camara", "ajustes")), listOf("app")))
            .put(tool("cambiar_tema", "Cambia el tema visual del launcher. Temas: $themes",
                JSONObject().put("tema", str("Nombre del tema")), listOf("tema")))
    }

    private fun systemPrompt(ctx: Context): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", Locale("es", "MX")).format(Date())
        val loc = if (CarState.lat != null) "%.4f, %.4f".format(CarState.lat, CarState.lon) else "desconocida"
        return """
            Eres el asistente de voz del Toyota Camry 2008 de Diana y Jahaz. Vives en el estéreo del carro y tus respuestas se leen en voz alta mientras manejan.
            Reglas:
            - Responde en español de México, natural y amable, en una o dos frases cortas.
            - Nada de listas, emojis, markdown, URLs ni símbolos raros: todo se va a escuchar.
            - Si te piden algo que puedes hacer con una herramienta, úsala y confirma en pocas palabras.
            - La seguridad va primero: no animes a usar el celular ni a distraerse; si algo requiere mirar la pantalla mucho rato, sugiere hacerlo al detenerse.
            - No tienes tráfico, clima ni noticias en vivo; si te lo piden, dilo con honestidad y sugiere abrir Maps.
            Datos del momento: $now. Velocidad: ${CarState.speedKmh.toInt()} km/h. Ubicación aproximada (lat, lon): $loc.
            Canción actual: ${CarState.song ?: "ninguna"}. Casa guardada: ${if (CarActions.home(ctx).isBlank()) "no" else "sí"}. Trabajo guardado: ${if (CarActions.work(ctx).isBlank()) "no" else "sí"}.
        """.trimIndent()
    }

    /**
     * Hace la pregunta (bloqueante, llamar desde un hilo de fondo).
     * [runTool] ejecuta cada herramienta en el hilo principal y devuelve el resultado.
     */
    fun ask(ctx: Context, question: String, runTool: (String, JSONObject) -> String): String {
        val key = apiKey(ctx)
        if (key.isBlank()) return "Primero pega tu llave de la API de Claude en Opciones del launcher."
        // la conversación se reinicia si pasaron más de 5 minutos
        val now = System.currentTimeMillis()
        if (now - lastUse > 5 * 60_000L) while (history.length() > 0) history.remove(0)
        lastUse = now
        history.put(JSONObject().put("role", "user").put("content", question))

        repeat(4) {
            val body = JSONObject()
                .put("model", model(ctx))
                .put("max_tokens", 400)
                .put("system", systemPrompt(ctx))
                .put("tools", tools(ctx))
                .put("messages", history)
            val resp = try {
                post(key, body)
            } catch (e: ApiError) {
                history.remove(history.length() - 1)
                return e.message ?: "Hubo un problema con el asistente."
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                return "No tengo conexión a internet en este momento."
            }
            val content = resp.optJSONArray("content") ?: JSONArray()
            history.put(JSONObject().put("role", "assistant").put("content", content))
            if (resp.optString("stop_reason") != "tool_use") {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.optString("type") == "text") sb.append(b.optString("text")).append(' ')
                }
                trimHistory()
                return sb.toString().trim().ifEmpty { "Listo." }
            }
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "tool_use") {
                    val out = runCatching { runTool(b.optString("name"), b.optJSONObject("input") ?: JSONObject()) }
                        .getOrElse { "Error: ${it.message}" }
                    results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", out))
                }
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        trimHistory()
        return "Listo."
    }

    /** Mantiene solo las últimas vueltas para que cada pregunta cueste poco. */
    private fun trimHistory() {
        while (history.length() > 12) {
            history.remove(0)
            // la conversación debe empezar con un mensaje de texto del usuario
            while (history.length() > 0) {
                val first = history.getJSONObject(0)
                if (first.optString("role") == "user" && first.opt("content") is String) break
                history.remove(0)
            }
        }
    }

    private class ApiError(msg: String) : Exception(msg)

    private fun post(key: String, body: JSONObject): JSONObject {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiError(
                    when (code) {
                        400 -> if (text.contains("credit", ignoreCase = true)) "Tu cuenta de la API se quedó sin saldo. Recárgala en la consola de Anthropic."
                               else "El asistente no entendió la solicitud."
                        401 -> "La llave de la API no es válida. Revísala en Opciones."
                        402, 403 -> "La cuenta de la API no tiene saldo o permiso. Revisa la consola de Anthropic."
                        429 -> "Hay demasiadas preguntas seguidas, intenta en un momento."
                        529, 503 -> "El servicio de Claude está saturado, intenta en un momento."
                        else -> "El asistente respondió con error $code."
                    }
                )
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
