package com.assistente.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val modelo = "openai/gpt-oss-120b"
    private val historico = JSONArray()
    private lateinit var chat: TextView
    private lateinit var rolagem: ScrollView
    private lateinit var entrada: EditText
    private lateinit var botaoMic: Button
    private lateinit var botaoVoz: Button

    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var falarRespostas = true
    private var reconhecedor: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(32, 120, 32, 32)

        val topo = LinearLayout(this)
        topo.orientation = LinearLayout.HORIZONTAL
        val botaoChave = Button(this)
        botaoChave.text = "Chave do Groq"
        botaoChave.setOnClickListener { pedirChave() }
        topo.addView(
            botaoChave,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        botaoVoz = Button(this)
        botaoVoz.text = "Voz: ligada"
        botaoVoz.setOnClickListener {
            falarRespostas = !falarRespostas
            botaoVoz.text = if (falarRespostas) "Voz: ligada" else "Voz: desligada"
            if (!falarRespostas) tts?.stop()
        }
        topo.addView(
            botaoVoz,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        raiz.addView(topo)

        rolagem = ScrollView(this)
        chat = TextView(this)
        chat.textSize = 16f
        chat.setPadding(8, 16, 8, 16)
        rolagem.addView(chat)
        raiz.addView(
            rolagem,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        botaoMic = Button(this)
        botaoMic.text = "Falar"
        botaoMic.setOnClickListener { iniciarEscuta() }
        raiz.addView(botaoMic)

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
                    "Você é um assistente pessoal. Responda em português do Brasil, de forma curta e direta. " +
                        "Suas respostas serão lidas em voz alta, então não use listas, asteriscos nem emojis."
                )
        )

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                ttsPronto = true
            }
        }

        if (chave().isEmpty()) pedirChave()
    }

    override fun onDestroy() {
        tts?.shutdown()
        reconhecedor?.destroy()
        super.onDestroy()
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

    private fun falar(texto: String) {
        if (falarRespostas && ttsPronto) {
            val limpo = texto.replace("*", "").replace("#", "")
            tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resposta")
        }
    }

    private fun iniciarEscuta() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        ouvir()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ouvir()
        } else {
            mostrar("Sem permissão do microfone, a voz não funciona.")
        }
    }

    private fun ouvir() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            mostrar("Reconhecimento de voz indisponível neste celular.")
            return
        }
        tts?.stop()
        if (reconhecedor == null) {
            reconhecedor = SpeechRecognizer.createSpeechRecognizer(this)
            reconhecedor?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    botaoMic.text = "Falar"
                    mostrar("Não consegui entender. Toque em Falar e tente de novo.")
                }

                override fun onResults(results: Bundle?) {
                    botaoMic.text = "Falar"
                    val lista = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val texto = lista?.firstOrNull()
                    if (!texto.isNullOrBlank()) enviar(texto)
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        botaoMic.text = "Ouvindo..."
        reconhecedor?.startListening(intent)
    }

    private fun enviarMensagem() {
        val texto = entrada.text.toString().trim()
        if (texto.isEmpty()) return
        entrada.setText("")
        enviar(texto)
    }

    private fun enviar(texto: String) {
        if (chave().isEmpty()) {
            pedirChave()
            return
        }
        mostrar("Você: $texto")
        historico.put(JSONObject().put("role", "user").put("content", texto))
        thread {
            val resposta = try {
                perguntarGroq()
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            runOnUiThread {
                mostrar("Assistente: $resposta")
                if (!resposta.startsWith("Erro")) falar(resposta)
            }
        }
    }

    private fun perguntarGroq(): String {
        val corpo = JSONObject().put("model", modelo).put("messages", historico)
        val con = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.setRequestProperty("Authorization", "Bearer " + chave())
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("User-Agent", "assistente-app/0.2")
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
 
