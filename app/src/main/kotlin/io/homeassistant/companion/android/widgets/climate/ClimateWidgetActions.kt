package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.CLIMATE_DOMAIN
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import timber.log.Timber

private val WIDGET_ID_KEY = ActionParameters.Key<Int>("CLIMATE_WIDGET_ID")
private val TEMPERATURE_DELTA_KEY = ActionParameters.Key<Float>("TEMPERATURE_DELTA")

/**
 * Returns an [androidx.glance.action.Action] that decreases the target temperature by one step.
 */
internal fun actionDecreaseTemperature(widgetId: Int, step: Float) =
    actionRunCallback<SetTemperatureAction>(
        ActionParameters.Builder()
            .set(WIDGET_ID_KEY, widgetId)
            .set(TEMPERATURE_DELTA_KEY, -step)
            .build(),
    )

/**
 * Returns an [androidx.glance.action.Action] that increases the target temperature by one step.
 */
internal fun actionIncreaseTemperature(widgetId: Int, step: Float) =
    actionRunCallback<SetTemperatureAction>(
        ActionParameters.Builder()
            .set(WIDGET_ID_KEY, widgetId)
            .set(TEMPERATURE_DELTA_KEY, step)
            .build(),
    )

/**
 * Returns an [androidx.glance.action.Action] that refreshes the climate widget.
 */
internal fun actionRefreshClimate() = actionRunCallback<RefreshClimateAction>()

/**
 * Refreshes the climate widget. This needs to be public because the Glance framework instantiates
 * it via reflection.
 */
class RefreshClimateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ClimateGlanceAppWidget().update(context, glanceId)
    }
}

/**
 * Sets the target temperature by applying a delta to the current target temperature stored in the
 * database. After a successful call to the server the widget is refreshed.
 *
 * This needs to be public because the Glance framework instantiates it via reflection.
 */
class SetTemperatureAction : ActionCallback {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SetTemperatureActionEntryPoint {
        fun serverManager(): ServerManager
        fun climateWidgetDao(): ClimateWidgetDao
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val widgetId = parameters[WIDGET_ID_KEY]
        val delta = parameters[TEMPERATURE_DELTA_KEY]

        if (widgetId == null || delta == null) {
            Timber.w("Aborting SetTemperatureAction because widgetId or delta is null")
            return
        }

        val entryPoints = EntryPoints.get(context.applicationContext, SetTemperatureActionEntryPoint::class.java)
        val dao = entryPoints.climateWidgetDao()
        val serverManager = entryPoints.serverManager()

        val widgetEntity = dao.get(widgetId)
        if (widgetEntity == null) {
            Timber.w("Aborting SetTemperatureAction — widget entity not found for id $widgetId")
            return
        }

        val lastData = widgetEntity.latestUpdateData
        if (lastData == null) {
            Timber.w("Aborting SetTemperatureAction — no last update data available")
            return
        }

        if (serverManager.getServer(widgetEntity.serverId) == null) {
            Timber.w("Aborting SetTemperatureAction — server has been removed")
            return
        }

        val currentTarget = lastData.targetTemperature ?: lastData.minTemperature
        val newTarget = (currentTarget + delta).coerceIn(lastData.minTemperature, lastData.maxTemperature)

        serverManager.integrationRepository(widgetEntity.serverId).callAction(
            domain = CLIMATE_DOMAIN,
            action = "set_temperature",
            actionData = mapOf(
                "entity_id" to widgetEntity.entityId,
                "temperature" to newTarget,
            ),
        )

        // Optimistically update the cached target temperature so the widget shows the new value
        // immediately before the server pushes an entity update.
        dao.updateWidgetLastUpdate(
            widgetId = widgetId,
            lastUpdateData = lastData.copy(targetTemperature = newTarget),
        )

        ClimateGlanceAppWidget().update(context, glanceId)
    }
}

/**
 * Cycles the HVAC mode to the next available mode.
 *
 * This needs to be public because the Glance framework instantiates it via reflection.
 */
class CycleHvacModeAction : ActionCallback {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CycleHvacModeActionEntryPoint {
        fun serverManager(): ServerManager
        fun climateWidgetDao(): ClimateWidgetDao
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val glanceManager = GlanceAppWidgetManager(context)
        val widgetId = glanceManager.getAppWidgetId(glanceId)

        val entryPoints = EntryPoints.get(context.applicationContext, CycleHvacModeActionEntryPoint::class.java)
        val dao = entryPoints.climateWidgetDao()
        val serverManager = entryPoints.serverManager()

        val widgetEntity = dao.get(widgetId)
        if (widgetEntity == null) {
            Timber.w("Aborting CycleHvacModeAction — widget entity not found for id $widgetId")
            return
        }

        val lastData = widgetEntity.latestUpdateData
        if (lastData == null || lastData.hvacModes.isEmpty()) {
            Timber.w("Aborting CycleHvacModeAction — no HVAC modes available")
            return
        }

        if (serverManager.getServer(widgetEntity.serverId) == null) {
            Timber.w("Aborting CycleHvacModeAction — server has been removed")
            return
        }

        val currentIndex = lastData.hvacModes.indexOf(lastData.hvacMode)
        val nextMode = lastData.hvacModes[(currentIndex + 1) % lastData.hvacModes.size]

        serverManager.integrationRepository(widgetEntity.serverId).callAction(
            domain = CLIMATE_DOMAIN,
            action = "set_hvac_mode",
            actionData = mapOf(
                "entity_id" to widgetEntity.entityId,
                "hvac_mode" to nextMode,
            ),
        )

        dao.updateWidgetLastUpdate(
            widgetId = widgetId,
            lastUpdateData = lastData.copy(hvacMode = nextMode),
        )

        ClimateGlanceAppWidget().update(context, glanceId)
    }
}

/**
 * Returns an [androidx.glance.action.Action] that cycles the HVAC mode.
 */
internal fun actionCycleHvacMode() = actionRunCallback<CycleHvacModeAction>()
