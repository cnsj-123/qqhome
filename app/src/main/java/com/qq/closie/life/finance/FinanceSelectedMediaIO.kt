package com.qq.closie.life.finance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.qq.closie.data.ocr.OcrEngine
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit selected-URI access only. No gallery scan, temporary screenshot or persistent OCR text. */
object FinanceSelectedMediaIO {
    const val MAX_BYTES=32*1024*1024
    private val recognition=Mutex()
    private suspend fun read(context:Context,uri:Uri):Pair<FinanceSelectedDocument,ByteArray> = withContext(Dispatchers.IO) {
        require(uri.scheme=="content") {"请选择系统文件选择器中的文件"}
        val mime=context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/") || mime=="application/pdf") {"目前支持图片与 PDF 凭据"}
        val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use {input->
            ByteArrayOutputStream().use {output->
                val buffer=ByteArray(16*1024);var total=0
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer);if(count<0) break
                    total=Math.addExact(total,count);require(total<=MAX_BYTES) {"凭据超过 32 MiB，请选择较小文件"}
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }
        require(bytes.isNotEmpty()) {"文件为空或不可读取"}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        FinanceSelectedDocument(uri.toString(),mime,hash,bytes.size.toLong()) to bytes
    }
    suspend fun inspect(context:Context,uri:Uri):FinanceSelectedDocument {
        val (document,bytes)=read(context,uri)
        bytes.fill(0);return document
    }
    internal suspend fun inspectOrNull(context:Context,uri:Uri):FinanceSelectedDocument? = try {inspect(context,uri)}
        catch(e:CancellationException){throw e} catch(_:Exception){null}

    suspend fun recognize(context:Context,uri:Uri):Pair<FinanceSelectedDocument,FinanceOcrFields> = recognition.withLock {recognizeSelected(context,uri)}
    private suspend fun recognizeSelected(context:Context,uri:Uri):Pair<FinanceSelectedDocument,FinanceOcrFields> {
        val (document,bytes)=read(context,uri)
        var bitmap:Bitmap?=null
        try {
            require(document.mimeType.startsWith("image/")) {"OCR 只识别所选图片；PDF 可作为附件保存"}
            withContext(Dispatchers.Default+NonCancellable) {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=100_000_000) {"图片尺寸不可读取或过大"}
                var sample=1
                while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048) sample*=2
                // Own it before dispatching back: prompt cancellation may discard a returned bitmap.
                bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})) {"图片无法解码"}
            }
            bytes.fill(0)
            currentCoroutineContext().ensureActive()
            // ML Kit's Task owns the bitmap until completion. Cancellation must not recycle it early.
            val fields=withContext(NonCancellable) {
                val raw=OcrEngine.recognizeText(requireNotNull(bitmap)).getOrThrow()
                FinanceLocalOcrParser.parse(raw)
            }
            currentCoroutineContext().ensureActive()
            return document to fields
        } finally {bytes.fill(0);bitmap?.recycle()}
    }
}
