package io.homeassistant.companion.android.widgets.climate

import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.friendlyName
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
                old.textColor == new.textColor
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

    private suspend fun getEntityUpdatesFlow(serverId: Int, entityId: String): Flow<Entity?>? {
        if (serverManager.getServer(serverId) == null) {
            Timber.w("Server has been removed and the climate widget needs to be reconfigured")
            return null
        }

        val current = serverManager.integrationRepository(serverId).getEntity(entityId)
        val updates = serverManager.integrationRepository(serverId).getEntityUpdates(listOf(entityId))

        return updates?.onStart { current?.let { emit(it) } }
    }

    /**
     * Returns a [Flow] that emits [ClimateWidgetState] updates for the widget identified by
     * [widgetId]. Starts with the last cached state from the database so the widget is never blank,
     * then switches to live server updates whenever the widget is in composition.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateFlow(widgetId: Int): Flow<ClimateWidgetState> {
        val watchForChangeFlow = getWidgetEntityOnConfigurationChange(widgetId)
            .flatMapLatest { widgetEntity ->
                Timber.d("Climate widget $widgetId watching entity ${widgetEntity.entityId}")
                val serverId = widgetEntity.serverId
                val entityId = widgetEntity.entityId

                getEntityUpdatesFlow(serverId, entityId)
                    ?.filterNotNull()
                    ?.distinctUntilChanged()
                    ?.map { entity ->
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
                            entityName = entityName,
                            hvacMode = hvacMode,
                            currentTemperature = currentTemperature,
                            targetTemperature = targetTemperature,
                            temperatureUnit = temperatureUnit,
                        )
                    }
                    ?: flowOf(widgetEntity.toStateWithData())
            }

        return merge(getInitialStateFlow(widgetId), watchForChangeFlow).catch {
            Timber.e(it, "Error while watching climate widget $widgetId state")
        }.onCompletion {
            Timber.d("Stop watching climate widget $widgetId state")
        }
    }

    private fun ClimateWidgetEntity.toStateWithData(): ClimateStateWithData = ClimateStateWithData(
        backgroundType = backgroundType,
        textColor = textColor,
        serverId = serverId,
        entityId = entityId,
        entityName = entityName,
        hvacMode = hvacMode,
        currentTemperature = currentTemperature,
        targetTemperature = targetTemperature,
        temperatureUnit = temperatureUnit,
    )
}
