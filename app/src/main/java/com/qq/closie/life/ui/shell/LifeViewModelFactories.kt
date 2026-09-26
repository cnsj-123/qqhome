package com.qq.closie.life.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.ui.plan.PlanViewModel
import com.qq.closie.life.ui.reference.ReferenceViewModel

/**
 * Factories that let the Life OS ViewModels be created through `viewModel()` instead of `remember`.
 *
 * ### The bug this fixes
 *
 * `ReferenceViewModel` and `PlanViewModel` extend `androidx.lifecycle.ViewModel` and use
 * `viewModelScope`, but the shell instantiated them with `remember { ReferenceViewModel(...) }`.
 * `viewModelScope` is tied to the ViewModel's lifecycle — it is cancelled by `onCleared()`. A
 * ViewModel constructed by hand is never registered with a `ViewModelStore`, so **nothing ever calls
 * `onCleared()` on it**. The practical consequences:
 *
 *  - The scope outlives the composable that made it. On configuration change (rotation, dark-mode
 *    switch, a locale change) the shell recomposes, `remember` is discarded, a *second* ViewModel is
 *    built with a *second* `viewModelScope`, and the first one keeps running — its `stateIn` flows
 *    keep collecting from Room and holding a reference to the repository. Do that a few times and you
 *    have several live collectors per rotation, none of which will ever be cleaned up.
 *  - It defeats the reason these classes are ViewModels at all. Being a ViewModel is a promise about
 *    who owns the instance's lifetime; `remember` breaks that promise silently.
 *
 * ### Why a factory rather than Hilt / Koin
 *
 * The ViewModels need constructor arguments that only [LifeContainer] can supply. A factory is the
 * framework's own answer to exactly that, it is ~20 lines, and it adds no dependency. Pulling in a
 * DI framework to construct two objects would be a far larger change than the feature — and this is
 * a correction to how existing objects are created, not a place to introduce an architecture.
 *
 * ### Sharing one instance
 *
 * Both factories read from the same container, and the shell calls `viewModel()` with an explicit
 * `key`, so 资料库 and 阅读 resolve to the *same* `ReferenceViewModel` (they are two views over one
 * data set and must agree) while 计划 gets its own. Resolving through the store also means the
 * instance survives configuration changes instead of being rebuilt.
 */
class ReferenceViewModelFactory(
    private val container: LifeContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReferenceViewModel::class.java)) {
            "ReferenceViewModelFactory cannot create ${modelClass.name}"
        }
        return ReferenceViewModel(
            repository = container.referenceRepository,
            mediaRepository = container.mediaRepository
        ) as T
    }
}

class PlanViewModelFactory(
    private val container: LifeContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlanViewModel::class.java)) {
            "PlanViewModelFactory cannot create ${modelClass.name}"
        }
        return PlanViewModel(repository = container.planRepository) as T
    }
}
