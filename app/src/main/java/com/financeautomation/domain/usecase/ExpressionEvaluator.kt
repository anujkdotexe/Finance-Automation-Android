package com.financeautomation.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** What an amount box holds, as the screens must treat it. */
sealed interface AmountOutcome {
    /** Nothing typed. */
    data object Empty : AmountOutcome

    /** Typed but not finished (for example "5+"): no error is shown yet and Save stays disabled. */
    data object Incomplete : AmountOutcome

    /** A valid amount above zero, rounded half up to 2 decimals. */
    data class Value(val amount: Double) : AmountOutcome

    data object Unreadable : AmountOutcome
    data object TooLarge : AmountOutcome

    /** The result is zero or negative. */
    data object NotPositive : AmountOutcome

    val isError: Boolean get() = this is Unreadable || this is TooLarge || this is NotPositive
}

/**
 * Evaluates simple amount expressions typed into an amount field, for example "120+80", "450/3", "99.5*2" or
 * "1,200+300". Supports + - * / and parentheses, decimals, thousands commas and spaces. Hand written recursive
 * descent parser: nothing is ever executed, any unknown character makes the whole input invalid.
 * Pure Kotlin with zero Android framework dependencies.
 */
class ExpressionEvaluator {

    /** Returns the result rounded to 2 decimals, or null when the input is not a valid amount expression. */
    fun evaluate(input: String): Double? {
        val cleaned = strip(input)
        if (cleaned.isEmpty()) return null
        return try {
            val value = compute(cleaned) ?: return null
            if (value < BigDecimal.ZERO || value > MAX_AMOUNT) return null
            round(value)
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Classifies the text for the amount box: the one place that decides the error text and the Save state. */
    fun evaluateDetailed(input: String): AmountOutcome {
        val cleaned = strip(input)
        if (cleaned.isEmpty()) return AmountOutcome.Empty
        if (isIncomplete(cleaned)) return AmountOutcome.Incomplete
        return try {
            val value = compute(cleaned) ?: return AmountOutcome.Unreadable
            when {
                value > MAX_AMOUNT -> AmountOutcome.TooLarge
                round(value) <= 0.0 -> AmountOutcome.NotPositive
                else -> AmountOutcome.Value(round(value))
            }
        } catch (e: ArithmeticException) {
            AmountOutcome.Unreadable
        } catch (e: IllegalArgumentException) {
            AmountOutcome.Unreadable
        }
    }

    private fun strip(input: String) = input.filterNot { it == ',' || it.isWhitespace() }

    /** The one rounding rule: half up to 2 decimals. */
    private fun round(value: BigDecimal): Double = value.setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun isIncomplete(cleaned: String): Boolean {
        val last = cleaned.last()
        if (last in "+-*/(") return true
        if (last == '.' && (cleaned.length == 1 || !cleaned[cleaned.length - 2].isDigit())) return true
        return cleaned.count { it == '(' } > cleaned.count { it == ')' }
    }

    private fun compute(cleaned: String): BigDecimal? {
        val parser = Parser(cleaned)
        val value = parser.parseExpression()
        return if (parser.atEnd()) value else null
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var depth = 0

        fun atEnd(): Boolean = pos >= text.length

        // expression = term (('+' | '-') term)*
        fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (pos < text.length && (text[pos] == '+' || text[pos] == '-')) {
                val op = text[pos++]
                val right = parseTerm()
                value = if (op == '+') value.add(right) else value.subtract(right)
            }
            return value
        }

        // term = factor (('*' | '/') factor)*
        private fun parseTerm(): BigDecimal {
            var value = parseFactor()
            while (pos < text.length && (text[pos] == '*' || text[pos] == '/')) {
                val op = text[pos++]
                val right = parseFactor()
                value = if (op == '*') {
                    value.multiply(right)
                } else {
                    if (right.signum() == 0) throw ArithmeticException("division by zero")
                    value.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
                }
            }
            return value
        }

        // factor = '-' factor | '(' expression ')' | number
        private fun parseFactor(): BigDecimal {
            if (pos >= text.length) throw IllegalArgumentException("unexpected end")
            val c = text[pos]
            return when {
                c == '-' -> {
                    pos++
                    parseFactor().negate()
                }
                c == '(' -> {
                    pos++
                    if (++depth > MAX_DEPTH) throw IllegalArgumentException("too deep")
                    val inner = parseExpression()
                    if (pos >= text.length || text[pos] != ')') throw IllegalArgumentException("missing )")
                    pos++
                    depth--
                    inner
                }
                c.isDigit() || c == '.' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected '$c'")
            }
        }

        private fun parseNumber(): BigDecimal {
            val start = pos
            var dots = 0
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) {
                if (text[pos] == '.') dots++
                pos++
            }
            val token = text.substring(start, pos)
            if (dots > 1 || token == ".") throw IllegalArgumentException("bad number")
            return BigDecimal(token)
        }
    }

    private companion object {
        val MAX_AMOUNT: BigDecimal = BigDecimal("10000000000")
        const val DIVISION_SCALE = 10
        const val MAX_DEPTH = 50
    }
}
