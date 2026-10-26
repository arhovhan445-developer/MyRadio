package com.example.myradio

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startForegroundService(Intent(this, RadioService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun send(a: String) = startService(Intent(this, RadioService::class.java).setAction(a))

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            addView(TextView(context).apply {
                text = "Моё радио (тест)\nАрмянские радиостанции, новости, камеры и пробки. Без токенов."
                textSize = 16f
            })
            addView(btn("▶ Старт радио") {
                perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
            })
            addView(btn("⏭ Следующая станция") { send("NEXT") })
            addView(btn("🧪 Тест голоса (новости, камера, пробка)") { send("DEMO") })
            addView(btn("■ Стоп") { send("STOP") })
        })
    }
}
