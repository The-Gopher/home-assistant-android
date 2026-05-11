package io.homeassistant.companion.android.widgets.climate

import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import timber.log.Timber

/**
 * Provides the state flow for a [ClimateGlanceAppWidget] identified by its widget ID.
 *
 * The flow emits an initial state immediately from the database so the widget can render even when
 * the screen first turns on and the server has not yet responded. It then continues to emit updated
 * states whenever the server pushes entity state changes.
 */
internal class ClimateWidgetStateUpdater @Inject constructor(
    private val climateWidgetDao: ClimateWidgetDao,
    private val serverManager: ServerManager,
) {

    private fun getClimateEntityOnConfigurationChange(widgetId: Int): Flow<ClimateWidgetEntity> {
        return climateWidgetDao.getFlow(widgetId).filterNotNull().distinctUntilChanged { old, new ->
            old.entityId == new.entityId &&
                old.serverId == new.serverId &&
                old.backgroundType == new.backgroundType &&
                old.textColor == new.textColor
        }
    }

    private fun getInitialStateFlow(widgetId: Int): Flow<ClimateWidgetState> {
        return suspend { climateWidgetDao.get(widgetId) }.asFlow().map { widgetEntity ->
            if (widgetEntity == null) {
                ClimateWidgetEmptyState
            } else {
                ClimateWidgetDataState.from(widgetEntity) ?: ClimateWidgetEmptyState
            }
        }
    }

    /**
     * Observes and provides the state of the widget identified by the given [widgetId].
     *
     * The flow starts with a cached state from the database and then listens for live entity
     * updates from the server. When a new entity update arrives the database is updated and a
     * fresh [ClimateWidgetDataState] is emitted.
     *
     * @param widgetId The unique identifier of the widget whose state is being observed.
     * @return A [Flow] emitting [ClimateWidgetState] objects.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateFlow(widgetId: Int): Flow<ClimateWidgetState> {
        val watchForChangeFlow = getClimateEntityOnConfigurationChange(widgetId)
            .flatMapLatest { widgetEntity ->
                Timber.d("Got a new climate entity to watch $widgetEntity")
                val serverId = widgetEntity.serverId
                val entityId = widgetEntity.entityId

                if (serverManager.getServer(serverId) == null) {
                    Timber.w("Server has been removed — widget needs to be reconfigured")
                    return@flatMapLatest flowOf(ClimateWidgetDataState.from(widgetEntity) ?: ClimateWidgetEmptyState)
                }

                val currentEntity = serverManager.integrationRepository(serverId).getEntity(entityId)
                val entityUpdateFlow = serverManager.integrationRepository(serverId)
                    .getEntityUpdates(listOf(entityId))

                if (entityUpdateFlow == null) {
                    Timber.w("Integration returned null for entity updates — widget will not update automatically")
                }

                entityUpdateFlow?.filterNotNull()?.onStart {
                    currentEntity?.let { emit(it) }
                }?.distinctUntilChanged()?.map { entity ->
                    Timber.d("Got an update for climate entity $entity")
                    val newState = ClimateWidgetDataState.from(widgetEntity, entity)
                    climateWidgetDao.updateWidgetLastUpdate(
                        widgetId = widgetId,
                        lastUpdateData = ClimateWidgetEntity.LastUpdateData(
                            entityName = newState.entityName,
                            currentTemperature = newState.currentTemperature,
                            targetTemperature = newState.targetTemperature,
                            minTemperature = newState.minTemperature,
                            maxTemperature = newState.maxTemperature,
                            temperatureUnit = newState.temperatureUnit,
                            temperatureStep = newState.temperatureStep,
                            hvacMode = newState.hvacMode,
                            hvacModes = newState.hvacModes,
                        ),
                    )
                    newState
                } ?: flowOf(ClimateWidgetDataState.from(widgetEntity) ?: ClimateWidgetEmptyState)
            }

        return merge(getInitialStateFlow(widgetId), watchForChangeFlow).catch {
            Timber.e(it, "Error while watching for changes for climate widget $widgetId")
        }.onCompletion {
            Timber.d("Stop watching for changes for climate widget $widgetId")
        }
    }
}
