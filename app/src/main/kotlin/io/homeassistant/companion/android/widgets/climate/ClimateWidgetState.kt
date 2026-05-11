package io.homeassistant.companion.android.widgets.climate

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProviders
import androidx.glance.material.ColorProviders
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.util.compose.HomeAssistantGlanceTheme
import io.homeassistant.companion.android.util.compose.glanceHaLightColors
import io.homeassistant.companion.android.widgets.climate.ClimateWidgetState.Companion.getColors

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
                    glanceHaLightColors
                        .copy(
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

internal object LoadingClimateState : ClimateWidgetState

internal object EmptyClimateState : ClimateWidgetState

/**
 * Widget state that holds all displayable climate entity data.
 *
 * @param label The resolved display label for the widget (user-configured label, falling back to
 *   entity friendly name, then entity ID). Always non-null.
 * @param hvacMode The current HVAC mode reported by the entity (e.g. "heat", "cool", "off").
 * @param currentTemperature The measured temperature at the entity's location, if available.
 * @param targetTemperature The desired set-point temperature, if available.
 * @param temperatureUnit The temperature unit string (e.g. "°C" or "°F").
 * @param outOfSync Whether the widget is showing cached data because sync failed.
 */
internal data class ClimateStateWithData(
    override val backgroundType: WidgetBackgroundType,
    override val textColor: String?,
    val serverId: Int,
    val entityId: String,
    val label: String,
    val hvacMode: String?,
    val currentTemperature: String?,
    val targetTemperature: String?,
    val temperatureUnit: String?,
    val outOfSync: Boolean = false,
) : ClimateWidgetState
