package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import java.security.MessageDigest

/** Shared transactional mechanics; domain repositories retain every business decision. */
internal class FinanceCommandRunner(private val db: LifeDatabase, private val finance: FinanceRepository,
    private val clock: () -> Long) {
    suspend fun run(token: FinanceCommandToken, command: String, input: Any?, block: suspend () -> String): String =
        RestoreStartupGate.withBusinessAccessSuspending { db.withTransaction {
            val dao=db.financeV16Dao()
            val epoch=dao.controlRows().singleOrNull()?.restoreEpoch ?: 0L
            require(token.operationId.isNotBlank() && token.restoreEpoch==epoch) { "恢复后请重新打开编辑器" }
            val digest=MessageDigest.getInstance("SHA-256").digest(com.google.gson.Gson().toJson(input).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val prior=dao.commandReceiptRows().singleOrNull { it.operationId==token.operationId }
            if(prior!=null) {
                require(prior.command==command && prior.inputDigest==digest && prior.epoch==epoch) { "操作标识已被其他内容使用" }
                prior.resultId
            } else {
                val result=block()
                FinanceIntegrity.validate(readFinanceSnapshot(db,true))
                dao.putCommandReceipt(listOf(FinanceCommandReceiptEntity(token.operationId,command,digest,result,epoch,clock())))
                finance.journal(result,command)
                result
            }
        } }
}
