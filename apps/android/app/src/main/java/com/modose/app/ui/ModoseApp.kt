package com.modose.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.modose.app.di.AppContainer
import com.modose.app.ui.theme.ModoseTheme

@Composable
fun ModoseApp(appContainer: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        ModoseTheme {
            Surface(modifier = Modifier.fillMaxSize()) {}
        }
    }
}
