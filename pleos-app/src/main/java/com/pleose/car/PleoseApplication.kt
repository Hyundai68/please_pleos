package com.pleose.car

import android.app.Application

class PleoseApplication : Application() {
    val runtime: CarRuntime by lazy { CarRuntime(this) }
}
