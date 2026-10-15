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
        var chartRead:suspend (UUID?,UUID?,String?,Set<UUID>?)->StatisticsChart={id,_,_,_-> SummaryJson.chart(SummaryFixture.chart().toString()).copy(gameId=id) }
        var detailRead:suspend (UUID,String,String?)->GameStatisticsPage={_,_,_-> SummaryJson.details(SummaryFixture.details().toString()) }
        override suspend fun load(onProgress:(Int)->Unit):StatisticsSnapshot=error("Must not scan Home or history")
        override suspend fun overview(revision:String?,cursor:String?):StatisticsOverview { overviewCalls++;return overviewRead(revision,cursor) }
        override suspend fun gameStatistics(id:UUID,revision:String,cursor:String?):GameStatisticsPage { detailCalls++;return detailRead(id,revision,cursor) }
        override suspend fun eloChart(gameId:UUID?,publication:UUID?,cursor:String?,players:Set<UUID>?):StatisticsChart {
            chartCalls++;return chartRead(gameId,publication,cursor,players)
        }
    }
    @Test fun `overview is visible while chart is still loading and repeated navigation uses the session`()=runTest {
        val repository=Repository();val chart=CompletableDeferred<StatisticsChart>()
        repository.chartRead={_,_,_,_->chart.await()}
        val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);runCurrent()
        assertNotNull(model.statistics.value.overview);assertTrue(model.statistics.value.charts[null]!!.loading)
        model.show(0);runCurrent();assertEquals(1,repository.overviewCalls);assertEquals(1,repository.chartCalls)
        chart.complete(SummaryJson.chart(SummaryFixture.chart().toString()));advanceUntilIdle()
        assertNotNull(model.statistics.value.charts[null]!!.data);model.clearMemory()
    }
    @Test fun `details load only at expansion and reopening preserves successful results`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle();assertEquals(0,repository.detailCalls)
        model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertEquals(1,repository.detailCalls);assertEquals(2,repository.chartCalls)
        model.toggleGame(SummaryFixture.game);model.toggleGame(SummaryFixture.game);advanceUntilIdle()
        assertEquals(1,repository.detailCalls);assertEquals(2,repository.chartCalls)
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
    @Test fun `different revisions cannot combine chart and game totals`()=runTest {
        val repository=Repository();repository.chartRead={id,_,_,_->SummaryJson.chart(SummaryFixture.chart().toString()).copy(gameId=id,revision="8")}
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);advanceUntilIdle()
        assertEquals(DataProblem.REVISION_CHANGED,model.statistics.value.problem)
        assertNull(model.statistics.value.overview);model.clearMemory()
    }
    @Test fun `auth failure removes all sections before access callback`()=runTest {
        val repository=Repository();val model=StatisticsDetailsViewModel(repository,failures::add)
        model.show(0);advanceUntilIdle()
        repository.chartRead={_,_,_,_->throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)}
        model.loadChart(null);advanceUntilIdle()
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
    @Test fun `chart pages replace the window bind publication and carry previous cursors`()=runTest {
        val repository=Repository();val args=mutableListOf<Pair<UUID?,String?>>()
        repository.chartRead={id,pub,cursor,_-> args+=pub to cursor
            SummaryJson.chart(SummaryFixture.chart().toString()).copy(gameId=id,next=if(cursor==null)"opaque-next" else null) }
        val model=StatisticsDetailsViewModel(repository,failures::add);model.show(0);advanceUntilIdle()
        model.nextChart(null);advanceUntilIdle()
        assertEquals(SummaryFixture.publication to "opaque-next",args.last())
        assertEquals(2,model.statistics.value.charts[null]!!.data!!.plays.size)
        assertEquals(listOf<String?>(null),model.statistics.value.charts[null]!!.previous)
        model.previousChart(null);advanceUntilIdle()
        assertEquals(SummaryFixture.publication to null,args.last());assertTrue(model.statistics.value.charts[null]!!.previous.isEmpty())
        model.clearMemory()
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
