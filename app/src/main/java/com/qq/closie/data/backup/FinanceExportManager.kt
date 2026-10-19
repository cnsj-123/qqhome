package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.life.finance.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

object FinanceExportManager {
    fun cleanInterruptedScratch(context:Context) {context.cacheDir.listFiles()?.filter {it.isFile && it.name.startsWith("finance-export-") && it.extension=="zip"}?.forEach {it.delete()}}
    suspend fun export(context:Context,finance:FinanceRepository,target:Uri):Result<Unit> =withContext(Dispatchers.IO) {
        var scratch:File?=null
        try {runCatching {
            val snapshot=finance.portableSnapshot()
            val file=File.createTempFile("finance-export-",".zip",context.cacheDir);scratch=file
            file.outputStream().use {FinancePortableExport.write(snapshot,it)}
            fun digest(input:java.io.InputStream):ByteArray=input.use {stream->val hash=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65_536)
                while(true){val count=stream.read(bytes);if(count<0)break;hash.update(bytes,0,count)};hash.digest()}
            val expected=digest(file.inputStream())
            requireNotNull(context.contentResolver.openOutputStream(target,"wt")){"目标文件无法写入"}.use {output->file.inputStream().use {it.copyTo(output)}}
            val actual=digest(requireNotNull(context.contentResolver.openInputStream(target)){"无法重新读取导出文件"})
            require(MessageDigest.isEqual(expected,actual)){"导出读回校验不一致，请重新导出"}
        }.onFailure {if(it is CancellationException) throw it}} finally {scratch?.delete()}
    }
}
