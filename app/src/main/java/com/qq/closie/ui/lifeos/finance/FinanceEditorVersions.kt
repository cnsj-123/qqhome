package com.qq.closie.ui.lifeos.finance

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Callers serialize each editor. Once a DB save starts, cancellation cannot lose its returned version. */
internal class FinanceEditorVersions {
    val mutex=Mutex()
    private val versions=mutableMapOf<String,Long?>()
    private val finished=mutableSetOf<String>()
    fun isFinished(editor:String)=editor in finished
    suspend fun persistLocked(editor:String,initial:Long?,write:suspend (Long?)->Unit):Long=withContext(NonCancellable) {
        val before=if(versions.containsKey(editor)) versions[editor] else initial
        write(before)
        Math.addExact(before ?: 0L,1L).also {versions[editor]=it}
    }
    suspend fun confirmLocked(editor:String,revision:Long,finish:Boolean=false,write:suspend ()->Unit)=withContext(NonCancellable) {
        write();versions[editor]=Math.addExact(revision,1L)
        if(finish) finished.add(editor)
    }
}
