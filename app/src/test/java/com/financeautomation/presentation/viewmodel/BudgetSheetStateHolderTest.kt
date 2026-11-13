package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSheetStateHolderTest {

    private class FakeBudgetRepository(initial: Map<String, Double> = emptyMap()) : BudgetRepository {
        val budgets = MutableStateFlow(initial)
        var failNext = false
        override fun observeBudgets(): Flow<List<Budget>> = throw UnsupportedOperationException()
        override suspend fun setBudget(category: String, monthlyAmount: Double): Result<Unit> {
            if (failNext) return Result.Error(IllegalStateException("boom"))
            budgets.value = budgets.value + (category to monthlyAmount)
            return Result.Success(Unit)
        }
        override suspend fun removeBudget(category: String): Result<Unit> {
            budgets.value = budgets.value - category
            return Result.Success(Unit)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun holder(repo: FakeBudgetRepository, existing: Double? = null) =
        BudgetSheetStateHolder("Food", existing, repo, scope, Dispatchers.Unconfined)

    @Test
    fun theLiveResultFollowsTheExpression() {
        val h = holder(FakeBudgetRepository())
        h.onInputChanged("1,200+300")
        assertEquals(1500.0, h.state.value.preview!!, 0.0)
        assertTrue(h.state.value.canSave)
        assertFalse(h.state.value.showInvalid)
    }

    @Test
    fun invalidInputIsRejectedAndNeverSaved() {
        val repo = FakeBudgetRepository()
        val h = holder(repo)
        for (bad in listOf("abc", "5/0", "0", "-5", "1+", "1e5")) {
            h.onInputChanged(bad)
            assertNull(bad, h.state.value.preview)
            assertTrue(bad, h.state.value.showInvalid)
            h.save()
        }
        assertTrue(repo.budgets.value.isEmpty())
        assertFalse(h.state.value.finished)
    }

    @Test
    fun savingWithoutAnyInputFlagsTheFieldAndSavesNothing() {
        val repo = FakeBudgetRepository()
        val h = holder(repo)
        h.save()
        assertTrue(h.state.value.showInvalid)
        assertTrue(repo.budgets.value.isEmpty())
    }

    @Test
    fun emptyTextIsNotFlaggedUntilSave() {
        val h = holder(FakeBudgetRepository())
        h.onInputChanged("")
        assertFalse(h.state.value.showInvalid)
        assertFalse(h.state.value.canSave)
    }

    @Test
    fun savingStoresTheEvaluatedAmountAndFinishes() {
        val repo = FakeBudgetRepository()
        val h = holder(repo)
        h.onInputChanged("450/3")
        h.save()
        assertEquals(mapOf("Food" to 150.0), repo.budgets.value)
        assertTrue(h.state.value.finished)
    }

    @Test
    fun anExistingBudgetIsShownInTheFieldAndEnablesClear() {
        val h = holder(FakeBudgetRepository(mapOf("Food" to 5000.0)), existing = 5000.0)
        assertEquals("5000", h.state.value.input)
        assertEquals(5000.0, h.state.value.preview!!, 0.0)
        assertTrue(h.state.value.hasExisting)
    }

    @Test
    fun clearRemovesTheBudget() {
        val repo = FakeBudgetRepository(mapOf("Food" to 5000.0, "Transit" to 800.0))
        val h = holder(repo, existing = 5000.0)
        h.clear()
        assertEquals(mapOf("Transit" to 800.0), repo.budgets.value)
        assertTrue(h.state.value.finished)
        assertFalse(h.state.value.hasExisting)
    }

    @Test
    fun clearDoesNothingWhenThereIsNoBudget() {
        val repo = FakeBudgetRepository(mapOf("Transit" to 800.0))
        val h = holder(repo, existing = null)
        h.clear()
        assertEquals(mapOf("Transit" to 800.0), repo.budgets.value)
        assertFalse(h.state.value.finished)
    }

    @Test
    fun aFailedSaveDoesNotFinishAndCanBeRetried() {
        val repo = FakeBudgetRepository()
        val h = holder(repo)
        h.onInputChanged("100")
        repo.failNext = true
        h.save()
        assertTrue(h.state.value.saveFailed)
        assertFalse(h.state.value.finished)
        repo.failNext = false
        h.save()
        assertTrue(h.state.value.finished)
        assertEquals(mapOf("Food" to 100.0), repo.budgets.value)
    }

    @Test
    fun editingTheTextClearsTheSaveError() {
        val repo = FakeBudgetRepository()
        val h = holder(repo)
        h.onInputChanged("100")
        repo.failNext = true
        h.save()
        h.onInputChanged("120")
        assertFalse(h.state.value.saveFailed)
    }
}
