package io.homeassistant.companion.android.widgets.climate

import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.friendlyName
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.util.sensitive
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import timber.log.Timber

/**
 * Provides a [Flow] of [ClimateWidgetState] for a given widget ID so that
 * [ClimateGlanceAppWidget] can observe live updates while in composition.
 */
internal class ClimateWidgetStateUpdater @Inject constructor(
    private val climateWidgetDao: ClimateWidgetDao,
    private val serverManager: ServerManager,
) {

    private fun getWidgetEntityOnConfigurationChange(widgetId: Int): Flow<ClimateWidgetEntity> {
        return climateWidgetDao.getFlow(widgetId).filterNotNull().distinctUntilChanged { old, new ->
            old.serverId == new.serverId &&
                old.entityId == new.entityId &&
                old.backgroundType == new.backgroundType &&
                old.textColor == new.textColor &&
                old.label == new.label
        }
    }

    private fun getInitialStateFlow(widgetId: Int): Flow<ClimateWidgetState> {
        return suspend { climateWidgetDao.get(widgetId) }.asFlow().map { entity ->
            if (entity == null) {
                EmptyClimateState
            } else {
                entity.toStateWithData()
            }
        }
    }

    /**
     * Returns a live entity-update flow for the configured climate entity.
     *
     * Returns `null` when the widget's server no longer exists, which signals callers to fall
     * back to cached widget data and show the out-of-sync indicator.
     */
    private suspend fun getEntityUpdatesFlow(serverId: Int, entityId: String): Flow<Entity?>? {
        if (serverManager.getServer(serverId) == null) {
            Timber.w("Server has been removed and the climate widget needs to be reconfigured")
            return null
        }

        val integrationRepository = serverManager.integrationRepository(serverId)
        val current = integrationRepository.getEntity(entityId)
        val updates = integrationRepository.getEntityUpdates(listOf(entityId))

        return updates?.onStart { current?.let { emit(it) } }
    }

    /**
     * Returns a [Flow] that emits [ClimateWidgetState] updates for the widget identified by
     * [widgetId]. Starts with the last cached state from the database so the widget is never blank,
     * then switches to live server updates whenever the widget is in composition.
     *
     * If live sync fails, the returned flow emits the latest cached state with the out-of-sync
     * flag set. The flow completes when the underlying DAO and entity-update flows complete.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateFlow(widgetId: Int): Flow<ClimateWidgetState> {
        val watchForChangeFlow = getWidgetEntityOnConfigurationChange(widgetId)
            .flatMapLatest { widgetEntity ->
                flow {
                    Timber.d(
                        "Climate widget ${sensitive { widgetId.toString() }} watching entity ${
                            sensitive(widgetEntity.entityId)
                        }",
                    )
                    val serverId = widgetEntity.serverId
                    val entityId = widgetEntity.entityId

                    val entityUpdatesFlow = getEntityUpdatesFlow(serverId, entityId)
                    if (entityUpdatesFlow == null) {
                        emit(getCurrentCachedState(widgetId = widgetId, fallbackEntity = widgetEntity, outOfSync = true))
                        return@flow
                    }

                    emitAll(
                        entityUpdatesFlow
                            .filterNotNull()
                            .distinctUntilChanged()
                            .map { entity ->
                                val attrs = entity.attributes as? Map<*, *> ?: emptyMap<String, Any>()
                                val hvacMode = entity.state
                                val currentTemperature = attrs["current_temperature"]?.toString()
                                val targetTemperature = attrs["temperature"]?.toString()
                                val temperatureUnit = attrs["unit_of_measurement"]?.toString()
                                val entityName = entity.friendlyName

                                climateWidgetDao.updateWidgetCachedState(
                                    widgetId = widgetId,
                                    entityName = entityName,
                                    hvacMode = hvacMode,
                                    currentTemperature = currentTemperature,
                                    targetTemperature = targetTemperature,
                                    temperatureUnit = temperatureUnit,
                                )

                                ClimateStateWithData(
                                    backgroundType = widgetEntity.backgroundType,
                                    textColor = widgetEntity.textColor,
                                    serverId = serverId,
                                    entityId = entityId,
                                    label = widgetEntity.label ?: entityName ?: entityId,
                                    hvacMode = hvacMode,
                                    currentTemperature = currentTemperature,
                                    targetTemperature = targetTemperature,
                                    temperatureUnit = temperatureUnit,
                                )
                            },
                    )
                }.catch { exception ->
                    Timber.e(exception, "Error while syncing climate widget $widgetId state")
                    emit(getCurrentCachedState(widgetId = widgetId, fallbackEntity = widgetEntity, outOfSync = true))
                }
            }

        return merge(getInitialStateFlow(widgetId), watchForChangeFlow).catch { exception ->
            Timber.e(exception, "Error while watching climate widget ${sensitive { widgetId.toString() }} state")
            emit(climateWidgetDao.get(widgetId)?.toStateWithData(outOfSync = true) ?: EmptyClimateState)
        }.onCompletion {
            Timber.d("Stop watching climate widget ${sensitive { widgetId.toString() }} state")
        }
    }

    private suspend fun getCurrentCachedState(
        widgetId: Int,
        fallbackEntity: ClimateWidgetEntity,
        outOfSync: Boolean,
    ): ClimateStateWithData {
        return climateWidgetDao.get(widgetId)?.toStateWithData(outOfSync = outOfSync)
            ?: fallbackEntity.toStateWithData(outOfSync = outOfSync)
    }

    private fun ClimateWidgetEntity.toStateWithData(outOfSync: Boolean = false): ClimateStateWithData = ClimateStateWithData(
        backgroundType = backgroundType,
        textColor = textColor,
        serverId = serverId,
        entityId = entityId,
        label = label ?: entityName ?: entityId,
        hvacMode = hvacMode,
        currentTemperature = currentTemperature,
        targetTemperature = targetTemperature,
        temperatureUnit = temperatureUnit,
        outOfSync = outOfSync,
    )
}
