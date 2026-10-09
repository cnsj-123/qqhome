package com.qq.closie.navigation.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Explicit constructor wiring, owned by the destination's ViewModelStore. */
class PresentationFactory<T : ViewModel>(private val type: Class<T>, private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        require(modelClass.isAssignableFrom(type))
        return create() as V
    }
}
