package com.dpsmpx.dpclock

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dpsmpx.dpclock.ui.NightClock

/**
 * Заставка Android «Ночные часы DP Clock» (D-017, п. 4): включается на зарядке
 * по настройкам телефона, если телефон даёт выбрать заставку. Тот же экран,
 * что у кнопки в разделе «Часы».
 */
class NightDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@NightDream)
            setViewTreeSavedStateRegistryOwner(this@NightDream)
            setContent { NightClock() }
        }
        setContentView(view)
    }

    /** Заставка на экране с этого момента — для статистики сна (D-023, п. 7). */
    private var dreamingSince = 0L

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreamingSince = System.currentTimeMillis()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        recordNightClock(this, dreamingSince)
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
