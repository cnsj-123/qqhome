package com.qq.closie.life.finance

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Shared strict wall-clock contract for persisted review dates and final cash commands. */
object FinanceTime {
    private val formatter=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val anchorFormatter=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    fun input(time:Long):String=formatter.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
    fun anchorLabel(time:Long):String=anchorFormatter.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
    fun resolve(text:String,preservedTime:Long?):Long=preservedTime?.takeIf {input(it)==text.trim()} ?: parse(text)
    fun parse(text:String):Long=try {
        LocalDateTime.parse(text.trim(),formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch(_:java.time.DateTimeException) {throw IllegalArgumentException("发生时间请填写为 年-月-日 时:分，例如 2026-10-09 17:30")}
}
