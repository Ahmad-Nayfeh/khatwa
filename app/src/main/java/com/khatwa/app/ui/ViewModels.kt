package com.khatwa.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.khatwa.app.AppContainer
import com.khatwa.app.KhatwaApp

/** Creates a ViewModel that receives the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = KhatwaApp.container(LocalContext.current)
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create(container) as T
    }
    return viewModel(factory = factory)
}
