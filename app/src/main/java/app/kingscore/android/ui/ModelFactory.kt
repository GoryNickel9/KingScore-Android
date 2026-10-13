package app.kingscore.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

fun <T : ViewModel> modelFactory(create: () -> T) = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        @Suppress("UNCHECKED_CAST")
        return create() as V
    }
}
