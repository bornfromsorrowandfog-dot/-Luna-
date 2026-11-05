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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val modelo = "openai/gpt-oss-120b"
    private val maxMensagens = 30
    private val personalidade =
        "Você é um assistente pessoal. Responda em português do Brasil, de forma curta e direta. " +
            "Suas respostas serão lidas em voz alta, então não use listas, asteriscos nem emojis."

    private var conversas = JSONArray()
    private var atual: JSONObject = JSONObject()

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

        botaoVoz = criarBotao("Voz: ligada") {
            falarRespostas = !falarRespostas
            botaoVoz.text = if (falarRespostas) "Voz: ligada" else "Voz: desligada"
            if (!falarRespostas) tts?.stop()
        }
        raiz.addView(linhaDeBotoes(criarBotao("Chave do Groq") { pedirChave() }, botaoVoz))
        raiz.addView(
            linhaDeBotoes(
                criarBotao("Histórico") { mostrarHistorico() },
                criarBotao("Novo chat") { novoChat() }
            )
        )

        rolagem = ScrollView(this)
        chat = TextView(this)
        chat.textSize = 16f
        chat.setPadding(8, 16, 8, 16)
        rolagem.addView(chat)
        raiz.addView(
            rolagem,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        botaoMic = criarBotao("Falar") { iniciarEscuta() }
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
        linha.addView(criarBotao("Enviar") { enviarMensagem() })
        raiz.addView(linha)

        setContentView(raiz)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                ttsPronto = true
            }
        }

        carregar()
        atual = ultimaConversa() ?: novaConversa()
        redesenhar()

        if (chave().isEmpty()) pedirChave()
    }

    override fun onDestroy() {
        tts?.shutdown()
        reconhecedor?.destroy()
        super.onDestroy()
    }

    // ---------- interface ----------

    private fun criarBotao(texto: String, acao: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.setOnClickListener { acao() }
        return b
    }

    private fun linhaDeBotoes(vararg botoes: Button): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (b in botoes) {
            l.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        return l
    }

    private fun mostrar(texto: String) {
        chat.append(texto + "\n\n")
        rolagem.post { rolagem.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun redesenhar() {
        chat.text = ""
        val msgs = atual.getJSONArray("mensagens")
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val quem = if (m.getString("role") == "user") "Você" else "Assistente"
            chat.append(quem + ": " + m.getString("content") + "\n\n")
        }
        rolagem.post { rolagem.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- histórico ----------

    private fun arquivo() = File(filesDir, "conversas.json")

    private fun novaConversa(): JSONObject {
        return JSONObject()
            .put("id", System.currentTimeMillis())
            .put("titulo", "Nova conversa")
            .put("atualizado", System.currentTimeMillis())
            .put("mensagens", JSONArray())
    }

    private fun carregar() {
        conversas = try {
            if (arquivo().exists()) JSONArray(arquivo().readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun ultimaConversa(): JSONObject? {
        var melhor: JSONObject? = null
        for (i in 0 until conversas.length()) {
            val c = conversas.getJSONObject(i)
            if (melhor == null || c.getLong("atualizado") > melhor.getLong("atualizado")) {
                melhor = c
            }
        }
        return melhor
    }

    private fun gravar(conv: JSONObject) {
        if (conv.getJSONArray("mensagens").length() == 0) return
        conv.put("atualizado", System.currentTimeMillis())
        var achou = false
        for (i in 0 until conversas.length()) {
            if (conversas.getJSONObject(i).getLong("id") == conv.getLong("id")) {
                conversas.put(i, conv)
                achou = true
                break
            }
        }
        if (!achou) conversas.put(conv)
        try {
            arquivo().writeText(conversas.toString())
        } catch (e: Exception) {
        }
    }

    private fun novoChat() {
        tts?.stop()
        atual = novaConversa()
        redesenhar()
    }

    private fun mostrarHistorico() {
        val lista = ArrayList<JSONObject>()
        for (i in 0 until conversas.length()) lista.add(conversas.getJSONObject(i))
        if (lista.isEmpty()) {
            mostrar("Nenhuma conversa salva ainda.")
            return
        }
        lista.sortByDescending { it.getLong("atualizado") }
        val formato = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        val titulos = lista.map {
            formato.format(Date(it.getLong("atualizado"))) + " - " + it.getString("titulo")
        }.toTypedArray()

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Conversas (segure para apagar)")
            .setItems(titulos) { _, which ->
                tts?.stop()
                atual = lista[which]
                redesenhar()
            }
            .setNegativeButton("Fechar", null)
            .show()

        dialogo.listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle("Apagar esta conversa?")
                .setMessage(lista[pos].getString("titulo"))
                .setPositiveButton("Apagar") { _, _ ->
                    apagar(lista[pos])
                    dialogo.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
    }

    private fun apagar(c: JSONObject) {
        val id = c.getLong("id")
        val nova = JSONArray()
        for (i in 0 until conversas.length()) {
            val o = conversas.getJSONObject(i)
            if (o.getLong("id") != id) nova.put(o)
        }
        conversas = nova
        try {
            arquivo().writeText(conversas.toString())
        } catch (e: Exception) {
        }
        if (atual.getLong("id") == id) novoChat()
    }

    // ---------- chave ----------

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

    // ---------- voz ----------

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

    // ---------- conversa com a IA ----------

    private fun enviarMensagem() {
        val texto = entrada.text.toString().trim()
        if (texto.isEmpty()) return
        entrada.setText("")
        enviar(texto)
    }

    private fun montarMensagens(conv: JSONObject): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", personalidade))
        val msgs = conv.getJSONArray("mensagens")
        val inicio = maxOf(0, msgs.length() - maxMensagens)
        for (i in inicio until msgs.length()) arr.put(msgs.getJSONObject(i))
        return arr
    }

    private fun enviar(texto: String) {
        if (chave().isEmpty()) {
            pedirChave()
            return
        }
        val conv = atual
        val msgs = conv.getJSONArray("mensagens")
        if (msgs.length() == 0) conv.put("titulo", texto.take(40))
        msgs.put(JSONObject().put("role", "user").put("content", texto))
        gravar(conv)
        mostrar("Você: $texto")

        val paraEnviar = montarMensagens(conv)
        thread {
            var deuErro = false
            val resposta = try {
                perguntarGroq(paraEnviar)
            } catch (e: Exception) {
                deuErro = true
                "Erro: " + e.message
            }
            runOnUiThread {
                if (!deuErro) {
                    conv.getJSONArray("mensagens")
                        .put(JSONObject().put("role", "assistant").put("content", resposta))
                    gravar(conv)
                }
                if (conv === atual) {
                    mostrar("Assistente: $resposta")
                    if (!deuErro) falar(resposta)
                }
            }
        }
    }

    private fun perguntarGroq(mensagens: JSONArray): String {
        val corpo = JSONObject().put("model", modelo).put("messages", mensagens)
        val con = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.setRequestProperty("Authorization", "Bearer " + chave())
        con.setRequestProperty("Content-Type", "application/json")
        con.setRequestProperty("User-Agent", "assistente-app/0.3")
        con.connectTimeout = 15000
        con.readTimeout = 60000
        con.doOutput = true
        con.outputStream.use { it.write(corpo.toString().toByteArray()) }

        val codigo = con.responseCode
        val fluxo = if (codigo in 200..299) con.inputStream else con.errorStream
        val texto = fluxo?.bufferedReader()?.use { it.readText() } ?: ""
        if (codigo !in 200..299) throw Exception("HTTP $codigo: $texto")

        return JSONObject(texto)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
