package com.qq.closie.life.finance

import java.math.BigInteger

/** A small exact arithmetic parser for money. Never evaluates code or rounds an unrepresentable cent. */
object FinanceCalculator {
    private data class Fraction(val numerator:BigInteger,val denominator:BigInteger) {
        fun normalized():Fraction {
            require(denominator.signum()!=0) { "不能除以零" }
            val sign=if(denominator.signum()<0) -BigInteger.ONE else BigInteger.ONE
            val divisor=numerator.gcd(denominator)
            val n=numerator/divisor*sign; val d=denominator/divisor*sign
            require(n.bitLength()<=256 && d.bitLength()<=256) { "计算超出支持范围" }
            return Fraction(n,d)
        }
        operator fun plus(other:Fraction)=Fraction(numerator*other.denominator+other.numerator*denominator,denominator*other.denominator).normalized()
        operator fun minus(other:Fraction)=Fraction(numerator*other.denominator-other.numerator*denominator,denominator*other.denominator).normalized()
        operator fun times(other:Fraction)=Fraction(numerator*other.numerator,denominator*other.denominator).normalized()
        operator fun div(other:Fraction)=Fraction(numerator*other.denominator,denominator*other.numerator).normalized()
    }
    fun evaluate(expression:String,currencyCode:String="CNY"):Long {
        require(expression.length in 1..120) { "请输入有效金额或简短算式" }
        val raw=expression.replace("×","*").replace("÷","/").replace("＋","+").replace("－","-").replace("．",".")
            .filterNot(Char::isWhitespace)
        require(raw.isNotEmpty())
        val parser=Parser(raw)
        val result=parser.expression()
        require(parser.finished()) { "算式中有未识别内容" }
        val scaled=result.numerator*BigInteger.TEN.pow(FinanceMoney.fractionDigits(currencyCode))
        val parts=scaled.divideAndRemainder(result.denominator)
        require(parts[1]==BigInteger.ZERO) { "结果不能精确表示为此币种金额，请调整算式" }
        val minor=try { parts[0].longValueExact() } catch(_:ArithmeticException) { throw IllegalArgumentException("金额超出支持范围") }
        require(minor>0) { "金额必须大于 0" }
        return minor
    }
    private class Parser(private val text:String) {
        private var index=0
        private var depth=0
        private var operations=0
        fun finished()=index==text.length
        private fun consume(char:Char):Boolean=if(index<text.length && text[index]==char) { index++; true } else false
        private fun count() { operations++; require(operations<=40) { "算式过长" } }
        fun expression():Fraction {
            var value=term()
            while(true) when {
                consume('+') -> { count(); value+=term() }
                consume('-') -> { count(); value-=term() }
                else -> return value
            }
        }
        private fun term():Fraction {
            var value=atom()
            while(true) when {
                consume('*') -> { count(); value*=atom() }
                consume('/') -> { count(); value/=atom() }
                else -> return value
            }
        }
        private fun atom():Fraction {
            require(++depth<=12) { "括号或符号嵌套过深" }
            try {
                if(consume('+')) return atom()
                if(consume('-')) return Fraction(-BigInteger.ONE,BigInteger.ONE)*atom()
                if(consume('(')) { val value=expression(); require(consume(')')) { "括号不完整" }; return value }
                val start=index
                while(index<text.length && text[index].isDigit()) index++
                if(consume('.')) while(index<text.length && text[index].isDigit()) index++
                val number=text.substring(start,index)
                require(Regex("[0-9]+(\\.[0-9]+)?").matches(number) && number.length<=30) { "请输入完整的十进制金额" }
                val decimal=java.math.BigDecimal(number)
                return Fraction(decimal.unscaledValue(),BigInteger.TEN.pow(decimal.scale())).normalized()
            } finally { depth-- }
        }
    }
}
