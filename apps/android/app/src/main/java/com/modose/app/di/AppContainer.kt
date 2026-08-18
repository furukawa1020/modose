package com.modose.app.di

class AppContainer private constructor() {
    companion object {
        fun create(): AppContainer = AppContainer()
    }
}
