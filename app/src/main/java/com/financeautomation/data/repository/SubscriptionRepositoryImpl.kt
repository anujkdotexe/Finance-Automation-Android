package com.financeautomation.data.repository

import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.dao.SubscriptionCycleDao
import com.financeautomation.data.local.dao.SubscriptionDao
import com.financeautomation.data.local.entity.SubscriptionCycleEntity
import com.financeautomation.data.local.entity.SubscriptionEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.model.SubscriptionCycleWithSubscription
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/**
 * Room backed [SubscriptionRepository]. DAOs are resolved lazily through providers so the
 * encrypted database is never opened on the main thread.
 */
class SubscriptionRepositoryImpl(
    private val subscriptionDaoProvider: suspend () -> SubscriptionDao,
    private val cycleDaoProvider: suspend () -> SubscriptionCycleDao,
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val localGatewayProvider: (() -> ConfigSyncLocalGateway)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SubscriptionRepository {

    constructor(
        subscriptionDao: SubscriptionDao,
        cycleDao: SubscriptionCycleDao,
        localGateway: ConfigSyncLocalGateway? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = { System.currentTimeMillis() }
    ) : this(
        subscriptionDaoProvider = { subscriptionDao },
        cycleDaoProvider = { cycleDao },
        configSyncCoordinatorProvider = null,
        localGatewayProvider = localGateway?.let { { it } },
        ioDispatcher = ioDispatcher,
        clock = clock
    )

    private val localGateway: ConfigSyncLocalGateway?
        get() = localGatewayProvider?.invoke() ?: configSyncCoordinatorProvider?.invoke()?.localGateway

    override fun observeSubscriptions(): Flow<List<Subscription>> = flow {
        val dao = subscriptionDaoProvider()
        dao.getSubscriptionsFlow().collect { list -> emit(list.map { it.toDomain() }) }
    }.flowOn(ioDispatcher)

    private fun joined(
        cycles: Flow<List<SubscriptionCycleEntity>>,
        subscriptions: Flow<List<SubscriptionEntity>>
    ): Flow<List<SubscriptionCycleWithSubscription>> =
        combine(cycles, subscriptions) { cycleList, subList ->
            val byId = subList.associateBy { it.id }
            cycleList.mapNotNull { c ->
                byId[c.subscriptionId]?.let {
                    SubscriptionCycleWithSubscription(c.toDomain(), it.toDomain())
                }
            }
        }

    override fun observeOpenCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flow {
        val cycleDao = cycleDaoProvider()
        val subDao = subscriptionDaoProvider()
        emitAll(joined(cycleDao.getOpenCyclesFlow(), subDao.getSubscriptionsFlow()))
    }.flowOn(ioDispatcher)

    override fun observeNeedsReviewCycles(): Flow<List<SubscriptionCycleWithSubscription>> = flow {
        val cycleDao = cycleDaoProvider()
        val subDao = subscriptionDaoProvider()
        emitAll(joined(cycleDao.getNeedsReviewCyclesFlow(), subDao.getSubscriptionsFlow()))
    }.flowOn(ioDispatcher)

    override suspend fun getSubscription(id: String): Subscription? = withContext(ioDispatcher) {
        subscriptionDaoProvider().getById(id)?.toDomain()
    }

    override suspend fun getActiveSubscriptions(): List<Subscription> = withContext(ioDispatcher) {
        subscriptionDaoProvider().getActive().map { it.toDomain() }
    }

    override suspend fun upsertSubscription(subscription: Subscription): Unit = withContext(ioDispatcher) {
        subscriptionDaoProvider().insert(SubscriptionEntity.fromDomain(subscription, clock(), dirty = 1))
        configSyncCoordinatorProvider?.invoke()?.schedulePush()
    }

    override suspend fun deleteSubscription(id: String): Unit = withContext(ioDispatcher) {
        val gateway = localGateway
        val now = clock()
        if (gateway != null) {
            gateway.recordTombstone(
                com.financeautomation.core.constants.ConfigConstants.TYPE_SUBSCRIPTION,
                id,
                now
            )
        } else {
            Diagnostics.record("SubscriptionRepositoryImpl", "tombstone_skipped: no gateway or coordinator")
        }
        cycleDaoProvider().deleteForSubscription(id)
        subscriptionDaoProvider().deleteById(id)
        configSyncCoordinatorProvider?.invoke()?.schedulePush()
    }

    override suspend fun getCycle(id: String): SubscriptionCycle? = withContext(ioDispatcher) {
        cycleDaoProvider().getById(id)?.toDomain()
    }

    override suspend fun getCycleWithSubscription(id: String): SubscriptionCycleWithSubscription? =
        withContext(ioDispatcher) {
            val cycle = cycleDaoProvider().getById(id) ?: return@withContext null
            val sub = subscriptionDaoProvider().getById(cycle.subscriptionId) ?: return@withContext null
            SubscriptionCycleWithSubscription(cycle.toDomain(), sub.toDomain())
        }

    private suspend fun attach(cycles: List<SubscriptionCycleEntity>, activeOnly: Boolean): List<SubscriptionCycleWithSubscription> {
        if (cycles.isEmpty()) return emptyList()
        val byId = subscriptionDaoProvider().getAll().associateBy { it.id }
        return cycles.mapNotNull { c ->
            val sub = byId[c.subscriptionId] ?: return@mapNotNull null
            if (activeOnly && !sub.active) return@mapNotNull null
            SubscriptionCycleWithSubscription(c.toDomain(), sub.toDomain())
        }
    }

    override suspend fun getCyclesInWindow(
        fromEpochDay: Long,
        toEpochDay: Long
    ): List<SubscriptionCycleWithSubscription> = withContext(ioDispatcher) {
        attach(cycleDaoProvider().getInWindow(fromEpochDay, toEpochDay), activeOnly = true)
    }

    override suspend fun getDueCyclesUpTo(epochDay: Long): List<SubscriptionCycleWithSubscription> =
        withContext(ioDispatcher) {
            attach(cycleDaoProvider().getDueUpTo(epochDay), activeOnly = true)
        }

    override suspend fun createCycleIfMissing(subscriptionId: String, dueEpochDay: Long): Boolean =
        withContext(ioDispatcher) {
            val entity = SubscriptionCycleEntity(
                id = "cyc_${UUID.randomUUID().toString().replace("-", "").take(16)}",
                subscriptionId = subscriptionId,
                dueEpochDay = dueEpochDay,
                state = SubscriptionCycleState.DUE.name,
                transactionId = null,
                remindCount = 0,
                updatedAt = clock()
            )
            cycleDaoProvider().insertIgnore(entity) != -1L
        }

    override suspend fun updateCycle(cycle: SubscriptionCycle): Unit = withContext(ioDispatcher) {
        cycleDaoProvider().update(SubscriptionCycleEntity.fromDomain(cycle))
    }

    override suspend fun setCycleState(cycleId: String, state: SubscriptionCycleState, remindCount: Int?): Unit =
        withContext(ioDispatcher) {
            val dao = cycleDaoProvider()
            val existing = dao.getById(cycleId) ?: return@withContext
            dao.setState(cycleId, state.name, remindCount ?: existing.remindCount, clock())
        }

    override suspend fun markCyclePaidForSubscription(
        subscriptionId: String,
        nowEpochDay: Long,
        transactionId: String
    ): Boolean = withContext(ioDispatcher) {
        val dao = cycleDaoProvider()
        val target = dao.getOpenForSubscription(subscriptionId)
            .minByOrNull { abs(it.dueEpochDay - nowEpochDay) }
            ?: return@withContext false
        dao.markPaid(target.id, transactionId, clock())
        true
    }

    override suspend fun markCyclePaid(cycleId: String, transactionId: String): Unit = withContext(ioDispatcher) {
        cycleDaoProvider().markPaid(cycleId, transactionId, clock())
    }

    override suspend fun skipPausedCycles(
        subscriptionId: String,
        fromEpochDay: Long,
        toEpochDay: Long
    ): Unit = withContext(ioDispatcher) {
        val sub = subscriptionDaoProvider().getById(subscriptionId)?.toDomain() ?: return@withContext
        val schedule = SubscriptionScheduleUseCase()
        val dueDays = schedule.dueDates(sub.copy(active = true), fromEpochDay, toEpochDay, todayEpochDay = toEpochDay + 1)
        val dao = cycleDaoProvider()
        val now = clock()
        for (day in dueDays) {
            val entity = SubscriptionCycleEntity(
                id = "cyc_${UUID.randomUUID().toString().replace("-", "").take(16)}",
                subscriptionId = subscriptionId,
                dueEpochDay = day,
                state = SubscriptionCycleState.SKIPPED.name,
                transactionId = null,
                remindCount = 0,
                updatedAt = now
            )
            val rowId = dao.insertIgnore(entity)
            if (rowId == -1L) {
                val existing = dao.getOpenForSubscription(subscriptionId).firstOrNull { it.dueEpochDay == day }
                if (existing != null && existing.state == SubscriptionCycleState.DUE.name) {
                    dao.setState(existing.id, SubscriptionCycleState.SKIPPED.name, existing.remindCount, now)
                }
            }
        }
    }
}
