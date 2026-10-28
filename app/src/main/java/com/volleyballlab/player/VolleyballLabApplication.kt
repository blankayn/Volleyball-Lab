package com.volleyballlab.player

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.volleyballlab.player.data.push.PushNotifications
import com.volleyballlab.player.di.AppContainer

class VolleyballLabApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installAppCheckProvider()
        PushNotifications.ensureChannel(this)
        container = AppContainer(this)
    }
}

/**
 * Reaches the app graph from inside a `viewModelFactory { initializer { ... } }`.
 *
 * This is the seam that keeps ViewModels free of Android context. They take their
 * dependencies as constructor parameters - which is what makes them unit
 * testable - and this extension is the only place that knows those dependencies
 * come from the Application object.
 */
val CreationExtras.appContainer: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VolleyballLabApplication)
        .container
