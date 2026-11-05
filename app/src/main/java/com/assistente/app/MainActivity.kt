package com.assistente.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val modelo = "openai/gpt-oss-120b"
    private val historico = JSONArray()
    private lateinit var chat: TextView
    private lateinit var rolagem: ScrollView
    private lateinit var entrada: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(32, 120, 32, 32)

        val botaoChave = Button(this)
        botaoChave.text = "Definir chave do Groq"
        botaoChave.setOnClickListener { pedirChave() }
        raiz.addView(botaoChave)

        rolagem = ScrollView(this)
        chat = TextView(this)
        chat.textSize = 16f
        chat.setPadding(8, 16, 8, 16)
        rolagem.addView(chat)
        raiz.addView(
            rolagem,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val linha = LinearLayout(this)
        linha.orientation = LinearLayout.HORIZONTAL
        entrada = EditText(this)
        entrada.hint = "Digite sua mensagem"
        entrada.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        linha.addView(
            entrada,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val botaoEnviar = Button(this)
        botaoEnviar.text = "Enviar"
        botaoEnviar.setOnClickListener { enviarMensagem() }
        linha.addView(botaoEnviar)
        raiz.addView(linha)

        setContentView(raiz)

        historico.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "Você é um assistente pessoal. Responda em português do Brasil, de forma curta e direta."
                )
        )

        if (chave().isEmpty()) pedirChave()
    }

    private fun chave(): String {
        return getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("chave", "") ?: ""
    }

    private fun pedirChave() {
        val campo = EditText(this)
        campo.hint = "Cole a chave aqui"
        AlertDialog.Builder(this)
            .setTitle("Chave do Groq")
            .setView(campo)
            .setPositiveButton("Salvar") { _, _ ->
                getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .edit()
                    .putString("chave", campo.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrar(texto: String) {
        chat.append(texto + "\n\n")
        rolagem.post { rolagem.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun enviarMensagem() {
        val texto = entrada.text.toString().trim()
        if (texto.isEmpty()) return
        if (chave().isEmpty()) {
            pedirChave()
            return
        }
        entrada.setText("")
        mostrar("Você: $texto")
        historico.put(JSONObject().put("role", "user").put("content", texto))
        thread {
            val resposta = try {
                perguntarGroq()
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            runOnUiThread { mostrar("Assistente: $resposta") }
        }
    }

    private fun perguntarGroq(): String {
        val corpo = JSONObject().put("model", modelo).put("messages", historico)
        val con = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.setRequestProperty("Authorization", "Bearer " + chave())
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("User-Agent", "assistente-app/0.1")
        con.connectTimeout = 15000
        con.readTimeout = 60000
        con.doOutput = true
        con.outputStream.use { it.write(corpo.toString().toByteArray()) }

        val codigo = con.responseCode
        val fluxo = if (codigo in 200..299) con.inputStream else con.errorStream
        val texto = fluxo?.bufferedReader()?.use { it.readText() } ?: ""
        if (codigo !in 200..299) return "Erro $codigo: $texto"

        val conteudo = JSONObject(texto)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        historico.put(JSONObject().put("role", "assistant").put("content", conteudo))
        return conteudo
    }
}
