package io.github.m1omg.opuscast

import android.app.Application
import android.content.Context

class OpusCastApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}

val Context.engine: Engine get() = (applicationContext as OpusCastApp).engine
