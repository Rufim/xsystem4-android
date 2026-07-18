package io.github.rufim.alice.ui

import android.app.Activity
import android.app.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose поверх SDL-активити. SDLActivity — не ComponentActivity, поэтому у её
 * view-дерева нет владельцев Lifecycle/SavedState/ViewModelStore, которых требует
 * ComposeView. Здесь они создаются вручную и привязываются к дереву.
 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun attachTo(view: android.view.View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

object ComposeOverlay {
    /** Полноэкранный Compose-экран поверх игры (диалог без темы/тайтла). */
    fun showFullscreen(activity: Activity, content: @Composable (dismiss: () -> Unit) -> Unit): Dialog {
        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val owner = OverlayOwner()
        val view = ComposeView(activity)
        dlg.setContentView(view)
        dlg.window?.decorView?.let { owner.attachTo(it) }
        owner.attachTo(view)
        view.setContent {
            AliceTheme { content { dlg.dismiss() } }
        }
        dlg.setOnDismissListener { owner.destroy() }
        dlg.show()
        return dlg
    }

    /** Постоянный Compose-оверлей внутри layout'а игры (панель, индикаторы).
     *  Владельцы ставятся на decorView окна: Compose ищет их от корня дерева. */
    fun attach(activity: Activity, parent: android.view.ViewGroup,
               content: @Composable () -> Unit): ComposeView {
        val owner = OverlayOwner()
        activity.window?.decorView?.let { owner.attachTo(it) }
        val view = ComposeView(activity)
        owner.attachTo(view)
        view.setContent { AliceTheme(content) }
        parent.addView(view, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        return view
    }
}
