package com.financeautomation.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpressionEvaluatorTest {

    private val evaluator = ExpressionEvaluator()

    private fun eval(s: String) = evaluator.evaluate(s)

    @Test
    fun addsSubtractsMultipliesDivides() {
        assertEquals(200.0, eval("120+80")!!, 0.0)
        assertEquals(150.0, eval("450/3")!!, 0.0)
        assertEquals(199.0, eval("99.5*2")!!, 0.0)
        assertEquals(40.0, eval("100-60")!!, 0.0)
    }

    @Test
    fun thousandsCommasAndSpacesAreIgnored() {
        assertEquals(1500.0, eval("1,200+300")!!, 0.0)
        assertEquals(1500.0, eval(" 1 200 + 300 ")!!, 0.0)
    }

    @Test
    fun followsOperatorPrecedenceAndParentheses() {
        assertEquals(14.0, eval("2+3*4")!!, 0.0)
        assertEquals(20.0, eval("(2+3)*4")!!, 0.0)
        assertEquals(24.0, eval("((2+3)*4)+4")!!, 0.0)
    }

    @Test
    fun plainNumberAndDecimals() {
        assertEquals(250.0, eval("250")!!, 0.0)
        assertEquals(0.5, eval(".5")!!, 0.0)
        assertEquals(12.5, eval("12.5")!!, 0.0)
    }

    @Test
    fun resultIsRoundedToTwoDecimals() {
        assertEquals(3.33, eval("10/3")!!, 0.0)
        assertEquals(6.67, eval("20/3")!!, 0.0)
        assertEquals(0.67, eval("2/3")!!, 0.0)
    }

    @Test
    fun unaryMinusInsideIsAllowedAsLongAsTheResultIsNotNegative() {
        assertEquals(5.0, eval("10+(-5)")!!, 0.0)
        assertEquals(10.0, eval("--10")!!, 0.0)
    }

    @Test
    fun lettersAndCodeAreRejected() {
        assertNull(eval("abc"))
        assertNull(eval("12a"))
        assertNull(eval("1+x"))
        assertNull(eval("Math.max(1,2)"))
        assertNull(eval("1e5"))
        assertNull(eval("rm -rf"))
    }

    @Test
    fun emptyAndBlankAreRejected() {
        assertNull(eval(""))
        assertNull(eval("   "))
        assertNull(eval(",,"))
    }

    @Test
    fun divisionByZeroIsRejected() {
        assertNull(eval("5/0"))
        assertNull(eval("5/(2-2)"))
        assertNull(eval("5/0.00"))
    }

    @Test
    fun negativeResultsAreRejected() {
        assertNull(eval("5-10"))
        assertNull(eval("-1"))
    }

    @Test
    fun resultsAboveTenBillionAreRejectedAndTheLimitItselfIsAccepted() {
        assertEquals(10_000_000_000.0, eval("10000000000")!!, 0.0)
        assertNull(eval("10000000001"))
        assertNull(eval("99999*99999*99999"))
    }

    @Test
    fun malformedStructureIsRejected() {
        assertNull(eval("1+"))
        assertNull(eval("+1"))
        assertNull(eval("*2"))
        assertNull(eval("(1+2"))
        assertNull(eval("1+2)"))
        assertNull(eval("()"))
        assertNull(eval("1..2"))
        assertNull(eval("1.2.3"))
        assertNull(eval("."))
        assertNull(eval("2(3)"))
    }

    @Test
    fun veryDeepNestingFailsInsteadOfOverflowingTheStack() {
        assertNull(eval("(".repeat(5000) + "1" + ")".repeat(5000)))
    }
}
