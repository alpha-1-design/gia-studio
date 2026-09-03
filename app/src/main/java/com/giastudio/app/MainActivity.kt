package com.giastudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.giastudio.app.ui.GiaApp

class MainActivity : ComponentActivity() {

    private var controller: StudioController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ctrl = StudioController(applicationContext)
        controller = ctrl
        setContent {
            GiaApp(ctrl)
        }
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
