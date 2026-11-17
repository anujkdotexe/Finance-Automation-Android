package com.financeautomation.presentation.components

import com.financeautomation.R
import com.financeautomation.domain.usecase.AmountOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountFieldLogicTest {

    @Test
    fun errorTextMatchesTheOutcome() {
        assertEquals(R.string.amount_err_unreadable, amountErrorRes(AmountOutcome.Unreadable, zeroIsFine = false))
        assertEquals(R.string.amount_err_too_large, amountErrorRes(AmountOutcome.TooLarge, zeroIsFine = false))
        assertEquals(R.string.amount_err_zero, amountErrorRes(AmountOutcome.NotPositive, zeroIsFine = false))
        assertNull(amountErrorRes(AmountOutcome.NotPositive, zeroIsFine = true))
        assertNull(amountErrorRes(AmountOutcome.Incomplete, zeroIsFine = false))
        assertNull(amountErrorRes(AmountOutcome.Empty, zeroIsFine = false))
        assertNull(amountErrorRes(AmountOutcome.Value(5.0), zeroIsFine = false))
    }

    @Test
    fun textShrinksWhileItOverflowsAndStopsAtAMinimum() {
        assertEquals(1f, nextAmountTextScale(1f, overflows = false), 0f)
        var scale = 1f
        repeat(50) { scale = nextAmountTextScale(scale, overflows = true) }
        assertEquals(0.4f, scale, 0.0001f)
        assertTrue(nextAmountTextScale(1f, overflows = true) < 1f)
    }
}
