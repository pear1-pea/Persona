package com.example.persona.core.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@ExperimentalCoroutinesApi
class BaseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val viewModel = TestViewModel()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `block executes successfully`() = runTest {
        var executed = false
        viewModel.doSomething(
            block = { executed = true }
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assert(executed)
    }

    @Test
    fun `onError is invoked when block throws`() = runTest {
        var caught: Throwable? = null
        viewModel.doSomething(
            block = { throw IOException("Network error") },
            onError = { caught = it }
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assert(caught is IOException)
        assert(caught?.message == "Network error")
    }

    @Test
    fun `onError captures RuntimeException`() = runTest {
        var msg: String? = null
        viewModel.doSomething(
            block = { throw RuntimeException("Boom") },
            onError = { msg = it.message }
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assert(msg == "Boom")
    }
}

/** Test-only subclass that exposes protected [BaseViewModel.launchCatching]. */
class TestViewModel : BaseViewModel() {
    fun doSomething(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ) {
        launchCatching(block = block, onError = onError)
    }
}
