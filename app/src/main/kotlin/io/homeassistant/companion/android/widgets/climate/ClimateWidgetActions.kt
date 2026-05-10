package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
import io.homeassistant.companion.android.util.sensitive
import timber.log.Timber


/**
 * Get an Action that will refresh the climate widget once given to Glance.
 */
@Composable
internal fun actionRefreshClimate(): Action {
    return actionRunCallback<RefreshAction>()
}

@VisibleForTesting
internal val CLIMATE_TEMP_DIRECTION_KEY = ActionParameters.Key<Int>("CLIMATE_TEMP_DIRECTION")

@Composable
internal fun actionDecreaseTargetTemperature(): Action {
    return actionRunCallback<AdjustTargetTemperatureAction>(
        actionParametersOf(CLIMATE_TEMP_DIRECTION_KEY to -1),
    )
}

@Composable
internal fun actionIncreaseTargetTemperature(): Action {
    return actionRunCallback<AdjustTargetTemperatureAction>(
        actionParametersOf(CLIMATE_TEMP_DIRECTION_KEY to 1),
    )
}

/**
 * Basic action that will refresh the given widget. Use [actionRefreshClimate] to get the
 * Action for Glance.
 *
 * Note: This needs to be public since it is instantiated by the Glance framework.
 *
 * From the doc https://developer.android.com/design/ui/mobile/guides/widgets/widget_quality_guide#tier2-content:
 * > Widget must let users manually refresh content, if there is an expectation the data refreshes more frequently than the UI.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ClimateGlanceAppWidget().update(context, glanceId)
    }
}

class AdjustTargetTemperatureAction : ActionCallback {
    private companion object {
        private const val ENTITY_ID_KEY = "entity_id"
        private const val TEMPERATURE_KEY = "temperature"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AdjustTargetTemperatureActionEntryPoint {
        fun climateServerManager(): ServerManager
        fun climateWidgetDao(): ClimateWidgetDao
    }

    @VisibleForTesting
    fun getEntryPoints(context: Context): AdjustTargetTemperatureActionEntryPoint {
        return EntryPoints.get(context.applicationContext, AdjustTargetTemperatureActionEntryPoint::class.java)
    }

    @VisibleForTesting
    fun getGlanceManager(context: Context): GlanceAppWidgetManager {
        return GlanceAppWidgetManager(context)
    }

    @VisibleForTesting
    fun getStepSize(temperatureUnit: String?): Double {
        return 0.1
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val direction = parameters[CLIMATE_TEMP_DIRECTION_KEY]
        if (direction == null || direction == 0) {
            Timber.w("Aborting climate target temperature action because direction is invalid")
            return
        }

        val entryPoints = getEntryPoints(context)
        val serverManager = entryPoints.climateServerManager()
        val dao = entryPoints.climateWidgetDao()
        val appWidgetId = getGlanceManager(context).getAppWidgetId(glanceId)
        val widgetEntity = dao.get(appWidgetId)

        if (widgetEntity == null) {
            Timber.w(
                "Aborting climate target temperature action because widget entity is missing for id=${
                    sensitive { appWidgetId.toString() }
                }",
            )
            return
        }

        if (serverManager.getServer(widgetEntity.serverId) == null) {
            Timber.w("Aborting climate target temperature action because server has been removed")
            return
        }

        val currentTargetTemperature = widgetEntity.targetTemperature?.toDoubleOrNull()
        if (currentTargetTemperature == null) {
            Timber.w("Aborting climate target temperature action because current target temperature is unavailable")
            return
        }

        val stepSize = getStepSize(widgetEntity.temperatureUnit)
        val updatedTargetTemperature = currentTargetTemperature + (stepSize * direction)

        serverManager.integrationRepository(widgetEntity.serverId).callAction(
            domain = CLIMATE_DOMAIN,
            action = "set_temperature",
            actionData = hashMapOf(
                ENTITY_ID_KEY to widgetEntity.entityId,
                TEMPERATURE_KEY to updatedTargetTemperature,
            ),
        )

        dao.updateWidgetCachedState(
            widgetId = appWidgetId,
            entityName = widgetEntity.entityName,
            hvacMode = widgetEntity.hvacMode,
            currentTemperature = widgetEntity.currentTemperature,
            targetTemperature = updatedTargetTemperature.toString(),
            temperatureUnit = widgetEntity.temperatureUnit,
        )

        ClimateGlanceAppWidget().update(context, glanceId)
    }
}
