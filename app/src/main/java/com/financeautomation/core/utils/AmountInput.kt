package com.financeautomation.core.utils

/**
 * The one filter for every amount box. It cleans what was typed or pasted instead of rejecting the edit, so a paste
 * such as "Rs 1,00,000.50" becomes "100000.50". Pure Kotlin, no Android classes.
 *
 * Kept characters: 0-9 . + - * / ( ). Any Unicode decimal digit becomes 0-9, x and the times and divide signs become
 * * and /, the minus sign becomes -. Everything else (spaces, tabs, newlines, commas, zero-width and bidi marks,
 * letters, currency signs) is removed.
 * Limits: 14 characters in total, 10 integer digits and 2 decimals per number, bracket depth 3, no closer without an
 * opener, no leading zeros beyond one, no doubled operators (the last one wins), no leading * / or +.
 */
object AmountInput {

    const val MAX_LENGTH = 14
    const val MAX_INTEGER_DIGITS = 10
    const val MAX_DECIMALS = 2
    const val MAX_DEPTH = 3

    private const val OPERATORS = "+-*/"

    fun clean(raw: String): String {
        val out = StringBuilder()
        var number = StringBuilder()
        var hasDot = false
        var decimals = 0
        var depth = 0

        fun resetNumber() {
            number = StringBuilder()
            hasDot = false
            decimals = 0
        }

        for (c in normaliseAll(raw)) {
            val last = out.lastOrNull()
            when {
                c in '0'..'9' -> {
                    if (last == ')') continue
                    if (hasDot) {
                        if (decimals >= MAX_DECIMALS || out.length >= MAX_LENGTH) continue
                        out.append(c)
                        decimals++
                    } else if (number.toString() == "0") {
                        if (c == '0') continue
                        out.setLength(out.length - 1)
                        out.append(c)
                        number = StringBuilder().append(c)
                    } else {
                        if (number.length >= MAX_INTEGER_DIGITS || out.length >= MAX_LENGTH) continue
                        out.append(c)
                        number.append(c)
                    }
                }
                c == '.' -> {
                    if (hasDot || last == ')' || out.length >= MAX_LENGTH) continue
                    out.append(c)
                    hasDot = true
                }
                c in OPERATORS -> {
                    if (last == null || last == '(') {
                        // only a minus may start the text or follow an opening bracket
                        if (c != '-') continue
                    }
                    if (last != null && last in OPERATORS) {
                        // keep the last operator, but a leading sign can only be a minus
                        val signPosition = out.length == 1 || out[out.length - 2] == '('
                        if (signPosition && c != '-') continue
                        out.setLength(out.length - 1)
                    } else if (out.length >= MAX_LENGTH) {
                        continue
                    }
                    out.append(c)
                    resetNumber()
                }
                c == '(' -> {
                    if (last != null && (last in '0'..'9' || last == '.' || last == ')')) continue
                    if (depth >= MAX_DEPTH || out.length >= MAX_LENGTH) continue
                    out.append(c)
                    depth++
                    resetNumber()
                }
                c == ')' -> {
                    if (depth == 0 || last == null || last in OPERATORS || last == '(' || out.length >= MAX_LENGTH) continue
                    out.append(c)
                    depth--
                    resetNumber()
                }
            }
        }
        return out.toString()
    }

    /**
     * Maps the whole text to kept characters. The letter x (or X) counts as a times sign only between two operands:
     * a digit or closing bracket before it and a digit, dot or opening bracket after it. Anywhere else it is dropped,
     * so "5 for tax" becomes 5 and not 5*.
     */
    private fun normaliseAll(raw: String): List<Char> {
        val kept = ArrayList<Char>(raw.length)
        for (ch in raw) {
            if (ch == 'x' || ch == 'X') kept.add('x') else normalise(ch)?.let { kept.add(it) }
        }
        val result = ArrayList<Char>(kept.size)
        for ((i, ch) in kept.withIndex()) {
            if (ch != 'x') {
                result.add(ch)
                continue
            }
            val prev = result.lastOrNull()
            val next = kept.subList(i + 1, kept.size).firstOrNull { it != 'x' }
            val leftIsOperand = prev != null && (prev in '0'..'9' || prev == ')')
            val rightIsOperand = next != null && (next in '0'..'9' || next == '.' || next == '(')
            if (leftIsOperand && rightIsOperand) result.add('*')
        }
        return result
    }

    /** Maps one typed character to a kept character, or null when it must be dropped. */
    private fun normalise(c: Char): Char? = when {
        c in '0'..'9' -> c
        c == '.' || c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' -> c
        c == '×' -> '*'
        c == '÷' -> '/'
        c == '−' -> '-'
        Character.isDigit(c) -> Character.digit(c, 10).takeIf { it in 0..9 }?.let { '0' + it }
        else -> null
    }
}
