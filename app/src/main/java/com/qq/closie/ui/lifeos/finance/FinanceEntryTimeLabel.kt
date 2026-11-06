package com.qq.closie.ui.lifeos.finance

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** The chip is concise; its caller retains the complete input in accessibility semantics. */
internal fun financeEntryTimeLabel(value:String,today:LocalDate=LocalDate.now()):String =
    runCatching {
        val at=LocalDateTime.parse(value.trim(),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT))
        val day=when(at.toLocalDate()) {
            today->"今天"
            today.minusDays(1)->"昨天"
            else->at.format(DateTimeFormatter.ofPattern(if(at.year==today.year) "M月d日" else "yyyy年M月d日"))
        }
        day+" "+at.format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(value)
