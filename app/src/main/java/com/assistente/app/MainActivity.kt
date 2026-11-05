package com.assistente.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = TextView(this)
        t.text = "Assistente funcionando!"
        t.textSize = 24f
        t.setPadding(48, 200, 48, 48)
        setContentView(t)
    }
}
