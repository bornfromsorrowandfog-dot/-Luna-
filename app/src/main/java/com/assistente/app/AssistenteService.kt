package com.assistente.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AssistenteService : AccessibilityService() {

    companion object {
        @Volatile var instancia: AssistenteService? = null
        @Volatile var parar = false
    }

    private val protegidos = listOf(
        "nubank", "com.nu.", "itau", "bradesco", "santander", "br.com.bb.", "caixa",
        "mercadopago", "picpay", "intermedium", "wallet", "bitwarden", "lastpass",
        "onepassword", "1password", "keepass", "authenticator", "bank", "banco"
    )

    private var botaoParar: View? = null
    private var painel: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        esconderParar()
        fecharPainel()
        instancia = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------- segurança ----------

    private fun protegido(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()?.lowercase() ?: return false
        return protegidos.any { pkg.contains(it) }
    }

    private fun rotuloDe(no: AccessibilityNodeInfo): String {
        val t = no.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return no.contentDescription?.toString()?.trim().orEmpty()
    }

    // ---------- ações na tela ----------

    fun clicarTexto(texto: String): Boolean {
        if (protegido()) return false
        val raiz = rootInActiveWindow ?: return false
        val alvo = texto.trim()
        if (alvo.isEmpty()) return false
        val lista = raiz.findAccessibilityNodeInfosByText(alvo)
        if (lista.isNullOrEmpty()) return false
        var escolhido: AccessibilityNodeInfo? = null
        for (n in lista) {
            if (rotuloDe(n).equals(alvo, ignoreCase = true)) {
                escolhido = n
                break
            }
        }
        if (escolhido == null) escolhido = lista[0]
        var n: AccessibilityNodeInfo? = escolhido
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun acharEditavel(no: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (no == null) return null
        if (no.isEditable) return no
        for (i in 0 until no.childCount) {
            val r = acharEditavel(no.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun digitar(texto: String): Boolean {
        if (protegido()) return false
        val raiz = rootInActiveWindow ?: return false
        val campo = raiz.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: acharEditavel(raiz)
            ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        return campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun acharRolavel(no: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (no == null) return null
        if (no.isScrollable) return no
        for (i in 0 until no.childCount) {
            val r = acharRolavel(no.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun rolar(paraBaixo: Boolean): Boolean {
        if (protegido()) return false
        val alvo = acharRolavel(rootInActiveWindow) ?: return false
        val acao = if (paraBaixo) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return alvo.performAction(acao)
    }

    fun global(nome: String): Boolean {
        val a = when (nome.trim().lowercase()) {
            "voltar" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recentes" -> GLOBAL_ACTION_RECENTS
            "notificacoes" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(a)
    }

    // ---------- leitura da tela ----------

    fun lerTela(): String {
        val raiz = rootInActiveWindow ?: return "(tela indisponível)"
        if (protegido()) return "(app financeiro ou de senhas: tela não lida por segurança)"
        val sb = StringBuilder()
        sb.append("App: ").append(raiz.packageName).append("\n")
        coletar(raiz, sb)
        return sb.toString().take(3500)
    }

    private fun coletar(no: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (no == null || sb.length > 3500) return
        if (!no.isVisibleToUser) return
        if (!no.isPassword) {
            val r = rotuloDe(no)
            if (r.isNotEmpty()) {
                val marca = when {
                    no.isEditable -> "[campo] "
                    no.isClickable -> "[toque] "
                    else -> ""
                }
                sb.append(marca).append(r.take(120)).append("\n")
            }
        }
        for (i in 0 until no.childCount) coletar(no.getChild(i), sb)
    }

    // ---------- janelas flutuantes (botão Parar e confirmação) ----------

    private fun gerenciador() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun parametros(largura: Int, gravidade: Int, y: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            largura,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = gravidade
        p.y = y
        return p
    }

    private fun fundo(cor: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(cor)
        g.cornerRadius = 32f
        return g
    }

    fun mostrarParar() {
        if (botaoParar != null) return
        val b = Button(this)
        b.text = "PARAR"
        b.setTextColor(Color.WHITE)
        b.background = fundo(Color.parseColor("#D32F2F"))
        b.setOnClickListener {
            parar = true
            esconderParar()
        }
        try {
            gerenciador().addView(
                b,
                parametros(ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END, 250)
            )
            botaoParar = b
        } catch (e: Exception) {
        }
    }

    fun esconderParar() {
        botaoParar?.let {
            try {
                gerenciador().removeView(it)
            } catch (e: Exception) {
            }
        }
        botaoParar = null
    }

    private fun fecharPainel() {
        painel?.let {
            try {
                gerenciador().removeView(it)
            } catch (e: Exception) {
            }
        }
        painel = null
    }

    fun confirmar(mensagem: String, aoSim: () -> Unit, aoNao: () -> Unit) {
        fecharPainel()
        val caixa = LinearLayout(this)
        caixa.orientation = LinearLayout.VERTICAL
        caixa.setPadding(48, 48, 48, 32)
        caixa.background = fundo(Color.WHITE)

        val t = TextView(this)
        t.text = "Executar ação?\n\n" + mensagem
        t.setTextColor(Color.BLACK)
        t.textSize = 16f
        caixa.addView(t)

        val linha = LinearLayout(this)
        linha.orientation = LinearLayout.HORIZONTAL
        val nao = Button(this)
        nao.text = "Não"
        nao.setOnClickListener {
            fecharPainel()
            aoNao()
        }
        val sim = Button(this)
        sim.text = "Sim"
        sim.setOnClickListener {
            fecharPainel()
            aoSim()
        }
        linha.addView(nao, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        linha.addView(sim, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        caixa.addView(linha)

        val largura = (resources.displayMetrics.widthPixels * 0.9).toInt()
        try {
            gerenciador().addView(caixa, parametros(largura, Gravity.CENTER, 0))
            painel = caixa
        } catch (e: Exception) {
            aoNao()
        }
    }
}
