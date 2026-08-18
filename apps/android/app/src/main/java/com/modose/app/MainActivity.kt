package com.modose.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.modose.app.ui.ModoseApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as ModoseApplication).appContainer
        setContent {
            ModoseApp(appContainer = appContainer)
        }
    }
}
