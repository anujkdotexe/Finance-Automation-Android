package com.financeautomation.presentation.viewmodel

import android.content.Context
import android.content.ContextWrapper
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupHealthCaptureStatusTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeSource(var granted: Boolean, var connected: Boolean) : CaptureStatusSource {
        override fun isNotificationAccessGranted() = granted
        override fun isListenerConnected() = connected
    }

    private fun viewModel(source: CaptureStatusSource) = SetupHealthViewModel(
        context = ContextWrapper(null) as Context,
        preferencesRepository = PreferencesRepositoryImpl(isTestHeadless = true),
        ioDispatcher = dispatcher,
        captureStatusSource = source
    )

    @Test
    fun captureIsOnOnlyWhenAccessGrantedAndListenerConnected() {
        val source = FakeSource(granted = true, connected = true)
        val vm = viewModel(source)
        assertTrue(vm.captureOn.value)
    }

    @Test
    fun captureIsOffWithoutAccess() {
        val vm = viewModel(FakeSource(granted = false, connected = true))
        assertFalse(vm.captureOn.value)
    }

    @Test
    fun captureIsOffWhenListenerDisconnected() {
        val vm = viewModel(FakeSource(granted = true, connected = false))
        assertFalse(vm.captureOn.value)
    }

    @Test
    fun refreshFollowsChangesInTheSource() {
        val source = FakeSource(granted = false, connected = false)
        val vm = viewModel(source)
        assertFalse(vm.captureOn.value)

        source.granted = true
        source.connected = true
        vm.refreshChecks()
        assertTrue(vm.captureOn.value)

        source.connected = false
        vm.refreshChecks()
        assertFalse(vm.captureOn.value)
    }

    @Test
    fun sourceFailureMeansCaptureOff() {
        val vm = viewModel(object : CaptureStatusSource {
            override fun isNotificationAccessGranted(): Boolean = throw IllegalStateException("boom")
            override fun isListenerConnected(): Boolean = true
        })
        assertFalse(vm.captureOn.value)
    }
}
