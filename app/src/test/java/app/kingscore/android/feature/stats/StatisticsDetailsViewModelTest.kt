package app.kingscore.android.feature.stats

import app.kingscore.android.core.auth.*
import app.kingscore.android.data.remote.SummaryFixture
import app.kingscore.android.data.remote.SummaryJson
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsDetailsViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val failures=mutableListOf<AuthProblem>()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class Repository : StatisticsRepository {
        var overviewCalls=0; var chartCalls=0; var detailCalls=0
        var overviewRead:suspend (String?,String?)->StatisticsOverview={_,_-> SummaryJson.overview(SummaryFixture.overview().toString()) }
        var detailRead:suspend (UUID,String,String?)->GameStatisticsPage={_,_,_-> SummaryJson.details(SummaryFixture.details().toString()) }
        override suspend fun load(onProgress:(Int)->Unit):StatisticsSnapshot=error("Must not scan Home or history")
        override suspend fun overview(revision:String?,cursor:String?):StatisticsOverview { overviewCalls++;return overviewRead(revision,cursor) }
        override suspend fun gameStatistics(id:UUID,revision:String,cursor:String?):GameStatisticsPage { detailCalls++;return detailRead(id,revision,cursor) }
        override suspend fun eloChart(gameId:UUID?,publication:UUID?,cursor:String?,players:Set<UUID>?):StatisticsChart {
            chartCalls++;error("Statistics must not request removed charts")
        }
    }
    @Test fun `overview and refresh load only numeric data and repeated navigation uses the session`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle()
        assertNotNull(model.statistics.value.overview)
        model.show(0);advanceUntilIdle();assertEquals(1,repository.overviewCalls)
        model.refresh();advanceUntilIdle();assertEquals(2,repository.overviewCalls)
        assertEquals(0,repository.chartCalls);model.clearMemory()
    }

    @Test fun `details load only at expansion and reopening preserves successful results`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle();assertEquals(0,repository.detailCalls)
        model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertEquals(1,repository.detailCalls);assertEquals(0,repository.chartCalls)
        model.toggleGame(SummaryFixture.game);model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertEquals(1,repository.detailCalls);assertEquals(0,repository.chartCalls)
        assertNotNull(model.statistics.value.games[SummaryFixture.game]!!.data)
        model.clearMemory()
    }
    @Test fun `pagination mismatch retries once then waits for explicit refresh`()=runTest {
        val repository=Repository();repository.overviewRead={_,_->throw DataFailure(DataProblem.REVISION_CHANGED)}
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);advanceUntilIdle()
        assertEquals(2,repository.overviewCalls)
        assertEquals(DataProblem.REVISION_CHANGED,model.statistics.value.problem)
        assertNull(model.statistics.value.overview)
        model.clearMemory()
    }
    @Test fun `different revisions cannot combine player details and game totals`()=runTest {
        val repository=Repository();repository.detailRead={_,_,_->SummaryJson.details(SummaryFixture.details().toString()).copy(revision="8")}
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);advanceUntilIdle()
        model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertEquals(DataProblem.REVISION_CHANGED,model.statistics.value.problem)
        assertNull(model.statistics.value.overview);assertEquals(0,repository.chartCalls);model.clearMemory()
    }

    @Test fun `auth failure removes all sections before access callback`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle()
        repository.detailRead={_,_,_->throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)}
        model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertNull(model.statistics.value.overview);assertTrue(model.statistics.value.games.isEmpty())
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE),failures)
    }
    @Test fun `late response after offline disposal or refresh never restores private data`()=runTest {
        val repository=Repository();val pending=CompletableDeferred<StatisticsOverview>()
        repository.overviewRead={_,_->withContext(NonCancellable){pending.await()}}
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);runCurrent()
        model.clearMemory();pending.complete(SummaryJson.overview(SummaryFixture.overview().toString()));advanceUntilIdle()
        assertNull(model.statistics.value.overview);assertTrue(model.statistics.value.games.isEmpty())
    }
    @Test fun `numeric game pages retain revision and append without requesting charts`()=runTest {
        val repository=Repository();val args=mutableListOf<Pair<String?,String?>>()
        val first=SummaryJson.overview(SummaryFixture.overview().toString())
        repository.overviewRead={revision,cursor -> args+=revision to cursor
            if(cursor==null) first.copy(totalGames=2,next="next-games")
            else first.copy(games=listOf(first.games.single().copy(id=UUID(0,99),name="Second game")),totalGames=2,next=null) }
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);advanceUntilIdle()
        model.moreGames();advanceUntilIdle()
        assertEquals(listOf(null to null,"7" to "next-games"),args)
        assertEquals(listOf(SummaryFixture.game,UUID(0,99)),model.statistics.value.overview!!.games.map{it.id})
        assertNull(model.statistics.value.overview!!.next);assertEquals(0,repository.chartCalls);model.clearMemory()
    }

    @Test fun `mutation refreshes expanded details and a new account starts empty`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle();model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        model.show(1);advanceUntilIdle();assertEquals(2,repository.overviewCalls);assertEquals(2,repository.detailCalls)
        model.clearMemory();assertTrue(model.statistics.value.expanded.isEmpty())
        val second=StatisticsDetailsViewModel(Repository(),failures::add);assertNull(second.statistics.value.overview)
        second.clearMemory()
    }
}
