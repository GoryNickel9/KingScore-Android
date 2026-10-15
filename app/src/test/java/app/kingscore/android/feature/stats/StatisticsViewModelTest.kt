package app.kingscore.android.feature.stats

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeStatistics()
    private val accessProblems = mutableListOf<AuthProblem>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `show scans once progress is not a snapshot and refresh retains the last complete snapshot`() = runTest(dispatcher) {
        val finish = CompletableDeferred<StatisticsSnapshot>()
        var calls = 0
        repository.scan = { progress -> calls++; progress(50); finish.await() }
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show()
        runCurrent()
        assertTrue(model.statistics.value.loading)
        assertEquals(50, model.statistics.value.processed)
        assertNull(model.statistics.value.snapshot)
        assertNull(model.statistics.value.updatedAt)
        model.show()
        runCurrent()
        assertEquals(1, calls)
        finish.complete(snapshot(51))
        advanceUntilIdle()
        assertEquals(snapshot(51), model.statistics.value.snapshot)
        assertEquals(51, model.statistics.value.processed)
        assertNotNull(model.statistics.value.updatedAt)
        model.show()
        advanceUntilIdle()
        assertEquals(1, calls)
        repository.scan = { snapshot(2) }
        val readAt = model.statistics.value.updatedAt
        model.refresh()
        assertEquals(snapshot(51), model.statistics.value.snapshot)
        assertEquals(readAt, model.statistics.value.updatedAt)
        assertTrue(model.statistics.value.loading)
        assertEquals(0, model.statistics.value.processed)
        advanceUntilIdle()
        assertEquals(snapshot(2), model.statistics.value.snapshot)
    }

    @Test fun `two screens coalesce the same mutation revision and explicit refresh still scans`() = runTest(dispatcher) {
        var calls = 0
        val first = CompletableDeferred<StatisticsSnapshot>()
        repository.scan = { calls++; first.await() }
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show(0)
        model.show(0)
        runCurrent()
        assertEquals(1, calls)
        first.complete(snapshot(5))
        advanceUntilIdle()
        repository.scan = { calls++; snapshot(calls) }
        model.refreshForRevision(1)
        model.refreshForRevision(1)
        model.show(1)
        advanceUntilIdle()
        assertEquals(2, calls)
        model.refresh()
        advanceUntilIdle()
        assertEquals(3, calls)
        model.clearMemory()
        model.show(1)
        advanceUntilIdle()
        assertEquals(4, calls)
    }

    @Test fun `a different account model never sees another models complete snapshot`() = runTest(dispatcher) {
        val first = StatisticsViewModel(repository, accessProblems::add)
        first.show(0)
        advanceUntilIdle()
        assertNotNull(first.statistics.value.snapshot)
        val secondRepository = FakeStatistics()
        val pending = CompletableDeferred<StatisticsSnapshot>()
        secondRepository.scan = { pending.await() }
        val second = StatisticsViewModel(secondRepository, accessProblems::add)
        second.show(0)
        runCurrent()
        assertNull(second.statistics.value.snapshot)
        first.clearMemory()
        pending.complete(snapshot(7))
        advanceUntilIdle()
        assertNull(first.statistics.value.snapshot)
        assertEquals(snapshot(7), second.statistics.value.snapshot)
    }

    @Test fun `data error after progress exposes no partial or old totals and refresh recovers`() = runTest(dispatcher) {
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show()
        advanceUntilIdle()
        assertNotNull(model.statistics.value.snapshot)
        repository.scan = { progress -> progress(50); throw DataFailure(DataProblem.FORBIDDEN) }
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.statistics.value.loading)
        assertEquals(DataProblem.FORBIDDEN, model.statistics.value.problem)
        assertEquals(50, model.statistics.value.processed)
        assertNull(model.statistics.value.snapshot)
        assertNull(model.statistics.value.updatedAt)
        assertTrue(accessProblems.isEmpty())
        repository.scan = { snapshot(100) }
        model.refresh()
        advanceUntilIdle()
        assertEquals(snapshot(100), model.statistics.value.snapshot)
        assertNull(model.statistics.value.problem)
    }

    @Test fun `auth failure clears progress and totals before blocking authenticated shell`() = runTest(dispatcher) {
        repository.scan = { progress -> progress(50); throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
        lateinit var model: StatisticsViewModel
        model = StatisticsViewModel(repository) { problem ->
            assertNull(model.statistics.value.snapshot)
            assertEquals(0, model.statistics.value.processed)
            accessProblems += problem
        }
        model.show()
        advanceUntilIdle()
        assertEquals(listOf(AuthProblem.INVALID_CREDENTIALS), accessProblems)
        assertNull(model.statistics.value.updatedAt)
    }

    @Test fun `late noncancellable progress and response cannot replace refreshed data`() = runTest(dispatcher) {
        val stale = CompletableDeferred<StatisticsSnapshot>()
        repository.scan = { progress -> withContext(NonCancellable) {
            val result = stale.await()
            progress(999)
            result
        } }
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show()
        runCurrent()
        repository.scan = { progress -> progress(2); snapshot(2) }
        model.refresh()
        runCurrent()
        stale.complete(snapshot(999))
        advanceUntilIdle()
        assertEquals(snapshot(2), model.statistics.value.snapshot)
        assertEquals(2, model.statistics.value.processed)
        assertTrue(accessProblems.isEmpty())
    }

    @Test fun `clearMemory cancels late responses and permits a fresh show without retained state`() = runTest(dispatcher) {
        val stale = CompletableDeferred<StatisticsSnapshot>()
        repository.scan = { progress -> withContext(NonCancellable) {
            val result = stale.await()
            progress(500)
            result
        } }
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show()
        runCurrent()
        model.clearMemory()
        stale.complete(snapshot(500))
        advanceUntilIdle()
        assertEquals(StatisticsState(), model.statistics.value)
        repository.scan = { snapshot(3) }
        model.show()
        advanceUntilIdle()
        assertEquals(snapshot(3), model.statistics.value.snapshot)
    }

    @Test fun `late error from superseded scan does not reject current account`() = runTest(dispatcher) {
        val failOld = CompletableDeferred<Unit>()
        repository.scan = { withContext(NonCancellable) {
            failOld.await()
            throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
        } }
        val model = StatisticsViewModel(repository, accessProblems::add)
        model.show()
        runCurrent()
        repository.scan = { snapshot(4) }
        model.refresh()
        runCurrent()
        failOld.complete(Unit)
        advanceUntilIdle()
        assertEquals(snapshot(4), model.statistics.value.snapshot)
        assertTrue(accessProblems.isEmpty())
    }

    private class FakeStatistics : StatisticsRepository {
        var scan: suspend ((Int) -> Unit) -> StatisticsSnapshot = { snapshot(1) }
        override suspend fun load(onProgress: (Int) -> Unit) = scan(onProgress)
    }

    companion object {
        private fun snapshot(count: Int) = StatisticsSnapshot(count, count, 0, 0, 0L, 0, emptyList(), null, 0, 0)
    }
}
