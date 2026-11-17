package com.financeautomation.presentation.ui

import com.financeautomation.presentation.ui.SetupGate.Decision
import com.financeautomation.presentation.ui.SetupGate.Pref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGateTest {

    private val loading = Pref.Loading
    private fun loaded(v: String?) = Pref.Loaded(v)

    @Test
    fun `nothing emitted yet is loading and not incomplete`() {
        assertEquals(Decision.LOADING, SetupGate.decide(loading, loading, loading))
    }

    @Test
    fun `one flow still loading keeps the decision loading`() {
        assertEquals(Decision.LOADING, SetupGate.decide(loaded("a@b.c"), loading, loaded(null)))
        assertEquals(Decision.LOADING, SetupGate.decide(loading, loaded("id"), loaded("id")))
        assertEquals(Decision.LOADING, SetupGate.decide(loaded("a@b.c"), loaded("id"), loading))
    }

    @Test
    fun `loading then complete never latches`() {
        var latched = false
        val steps = listOf(
            SetupGate.decide(loading, loading, loading),
            SetupGate.decide(loaded("a@b.c"), loading, loading),
            SetupGate.decide(loaded("a@b.c"), loaded("id"), loaded(null))
        )
        for (d in steps) latched = SetupGate.latch(latched, d)
        assertFalse(latched)
        assertEquals(Decision.COMPLETE, steps.last())
    }

    @Test
    fun `loading then incomplete latches and stays latched after sign in finishes`() {
        var latched = false
        latched = SetupGate.latch(latched, SetupGate.decide(loading, loading, loading))
        assertFalse(latched)
        latched = SetupGate.latch(latched, SetupGate.decide(loaded(null), loaded(null), loaded(null)))
        assertTrue(latched)
        latched = SetupGate.latch(latched, SetupGate.decide(loaded("a@b.c"), loaded("id"), loaded(null)))
        assertTrue(latched)
    }

    @Test
    fun `per year empty with legacy set counts as complete`() {
        assertEquals(Decision.COMPLETE, SetupGate.decide(loaded("a@b.c"), loaded(null), loaded("legacy")))
        assertEquals(Decision.COMPLETE, SetupGate.decide(loaded("a@b.c"), loaded("  "), loaded("legacy")))
    }

    @Test
    fun `missing email or missing both ids is incomplete`() {
        assertEquals(Decision.INCOMPLETE, SetupGate.decide(loaded(null), loaded("id"), loaded("id")))
        assertEquals(Decision.INCOMPLETE, SetupGate.decide(loaded(" "), loaded("id"), loaded("id")))
        assertEquals(Decision.INCOMPLETE, SetupGate.decide(loaded("a@b.c"), loaded(null), loaded(null)))
    }
}
