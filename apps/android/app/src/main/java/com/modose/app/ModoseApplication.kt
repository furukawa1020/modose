package com.modose.app

import android.app.Application
import com.modose.app.di.AppContainer

class ModoseApplication : Application() {
    val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer.create(applicationContext)
    }
}
