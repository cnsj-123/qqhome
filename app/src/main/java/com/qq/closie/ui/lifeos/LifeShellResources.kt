package com.qq.closie.ui.lifeos

import com.qq.closie.ClosieApplication
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.LifeContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real storage initialization happens off main while the composition can draw its startup state. */
internal suspend fun loadLifeShellResources(app:ClosieApplication):Pair<LifeContainer,WardrobeRepository> =
    withContext(Dispatchers.IO) {app.lifeContainer to app.wardrobeRepository}
