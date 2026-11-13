package com.financeautomation.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Evaluates simple amount expressions typed into an amount field, for example "120+80", "450/3", "99.5*2" or
 * "1,200+300". Supports + - * / and parentheses, decimals, thousands commas and spaces. Hand written recursive
 * descent parser: nothing is ever executed, any unknown character makes the whole input invalid.
 * Pure Kotlin with zero Android framework dependencies.
 */
class ExpressionEvaluator {

    /** Returns the result rounded to 2 decimals, or null when the input is not a valid amount expression. */
    fun evaluate(input: String): Double? {
        val cleaned = input.filterNot { it == ',' || it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        return try {
            val parser = Parser(cleaned)
            val value = parser.parseExpression()
            if (!parser.atEnd()) return null
            if (value < BigDecimal.ZERO || value > MAX_AMOUNT) return null
            value.setScale(2, RoundingMode.HALF_UP).toDouble()
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
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
