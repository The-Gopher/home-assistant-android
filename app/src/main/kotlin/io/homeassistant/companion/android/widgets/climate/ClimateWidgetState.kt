package io.homeassistant.companion.android.widgets.climate

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.material.ColorProviders
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.friendlyName
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.util.compose.HomeAssistantGlanceTheme
import io.homeassistant.companion.android.util.compose.glanceHaLightColors

internal sealed interface ClimateWidgetState {
    val backgroundType: WidgetBackgroundType
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WidgetBackgroundType.DYNAMICCOLOR
        } else {
            WidgetBackgroundType.DAYNIGHT
        }
    val textColor: String?
        get() = null

    companion object {
        @Composable
        fun ClimateWidgetState.getColors(): ColorProviders {
            return when (backgroundType) {
                WidgetBackgroundType.DYNAMICCOLOR -> GlanceTheme.colors
                WidgetBackgroundType.DAYNIGHT -> HomeAssistantGlanceTheme.colors
                WidgetBackgroundType.TRANSPARENT -> ColorProviders(
                    glanceHaLightColors.copy(
                        background = Color.Transparent,
                        onSurface = Color(
                            textColor?.toColorInt() ?: glanceHaLightColors.onSurface.toArgb(),
                        ),
                    ),
                )
            }
        }
    }
}

internal object ClimateWidgetLoadingState : ClimateWidgetState
internal object ClimateWidgetEmptyState : ClimateWidgetState

internal data class ClimateWidgetDataState(
    override val backgroundType: WidgetBackgroundType,
    override val textColor: String?,
    val serverId: Int,
    val entityId: String,
    val entityName: String?,
    val currentTemperature: Float?,
    val targetTemperature: Float?,
    val minTemperature: Float,
    val maxTemperature: Float,
    val temperatureUnit: String,
    val temperatureStep: Float,
    val hvacMode: String,
    val hvacModes: List<String>,
) : ClimateWidgetState {

    companion object {
        /**
         * Creates a [ClimateWidgetDataState] from a database entity and a live [Entity] received
         * from the server. The resulting state uses the freshest server data so [outOfSync] is
         * considered false.
         */
        fun from(widgetEntity: ClimateWidgetEntity, entity: Entity): ClimateWidgetDataState {
            val minTemp = (entity.attributes["min_temp"] as? Number)?.toFloat() ?: 0f
            val maxTemp = (entity.attributes["max_temp"] as? Number)?.toFloat() ?: 100f
            val targetTemp = (entity.attributes["temperature"] as? Number)?.toFloat()
            val currentTemp = (entity.attributes["current_temperature"] as? Number)?.toFloat()
            val temperatureUnit = entity.attributes["temperature_unit"] as? String ?: ""
            val temperatureStep = (entity.attributes["target_temp_step"] as? Number)?.toFloat()
                ?: if (temperatureUnit == "°C") 0.5f else 1f
            @Suppress("UNCHECKED_CAST")
            val hvacModes = (entity.attributes["hvac_modes"] as? List<String>) ?: emptyList()

            return ClimateWidgetDataState(
                backgroundType = widgetEntity.backgroundType,
                textColor = widgetEntity.textColor,
                serverId = widgetEntity.serverId,
                entityId = widgetEntity.entityId,
                entityName = entity.friendlyName,
                currentTemperature = currentTemp,
                targetTemperature = targetTemp?.coerceIn(minTemp, maxTemp),
                minTemperature = minTemp,
                maxTemperature = maxTemp,
                temperatureUnit = temperatureUnit,
                temperatureStep = temperatureStep,
                hvacMode = entity.state,
                hvacModes = hvacModes,
            )
        }

        /**
         * Creates a [ClimateWidgetDataState] from the database entity only, using the last known
         * cached data. Returns null when no cached data is available (widget not yet configured).
         */
        fun from(widgetEntity: ClimateWidgetEntity): ClimateWidgetDataState? {
            val data = widgetEntity.latestUpdateData ?: return null
            return ClimateWidgetDataState(
                backgroundType = widgetEntity.backgroundType,
                textColor = widgetEntity.textColor,
                serverId = widgetEntity.serverId,
                entityId = widgetEntity.entityId,
                entityName = data.entityName,
                currentTemperature = data.currentTemperature,
                targetTemperature = data.targetTemperature,
                minTemperature = data.minTemperature,
                maxTemperature = data.maxTemperature,
                temperatureUnit = data.temperatureUnit,
                temperatureStep = data.temperatureStep,
                hvacMode = data.hvacMode,
                hvacModes = data.hvacModes,
            )
        }
    }
}
