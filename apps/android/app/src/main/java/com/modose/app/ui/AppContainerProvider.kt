package com.modose.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.modose.app.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}
