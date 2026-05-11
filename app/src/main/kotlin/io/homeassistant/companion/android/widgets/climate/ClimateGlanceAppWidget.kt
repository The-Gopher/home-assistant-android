package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.util.compose.HomeAssistantGlanceTheme
import io.homeassistant.companion.android.util.compose.HomeAssistantGlanceTypography
import io.homeassistant.companion.android.util.compose.actionStartWebView
import io.homeassistant.companion.android.util.compose.glanceStringResource
import io.homeassistant.companion.android.widgets.climate.ClimateWidgetState.Companion.getColors
import kotlin.math.abs

/**
 * Glance widget for viewing and controlling a climate entity.
 *
 * This widget follows the guidelines from https://developer.android.com/design/ui/mobile/guides/widgets/widget_quality_guide
 *
 * It displays the current temperature, target temperature with +/- controls, and the current HVAC
 * mode. The entity and theme can be configured via [ClimateWidgetConfigureActivity].
 *
 * ### Limitations:
 * - Temperature is adjusted by one step per button press. There is no live feedback until the
 *   server confirms the change.
 * - HVAC mode cycles through available modes on tap.
 */
class ClimateGlanceAppWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ClimateGlanceWidgetEntryPoint {
        fun climateWidgetStateUpdater(): ClimateWidgetStateUpdater
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = GlanceAppWidgetManager(context)
        val widgetId = manager.getAppWidgetId(id)

        provideContent {
            val entryPoints = remember {
                EntryPoints.get(context, ClimateGlanceWidgetEntryPoint::class.java)
            }
            val flow = remember { entryPoints.climateWidgetStateUpdater().stateFlow(widgetId) }
            val state by flow.collectAsState(ClimateWidgetLoadingState)

            HomeAssistantGlanceTheme(colors = state.getColors()) {
                ScreenForState(state, widgetId)
            }
        }
    }
}

@Composable
private fun GlanceModifier.climateWidgetBackground(): GlanceModifier =
    appWidgetBackground().fillMaxSize().background(GlanceTheme.colors.widgetBackground)

@Composable
@VisibleForTesting
internal fun ScreenForState(state: ClimateWidgetState, widgetId: Int = 0) {
    when (state) {
        ClimateWidgetLoadingState -> LoadingScreen()
        ClimateWidgetEmptyState -> EmptyScreen()
        is ClimateWidgetDataState -> Screen(state, widgetId)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier.climateWidgetBackground().semantics { testTag = "LoadingScreen" },
    ) {
        CircularProgressIndicator(
            color = GlanceTheme.colors.primary,
            modifier = GlanceModifier.size(HomeAssistantGlanceTheme.dimensions.iconSize),
        )
    }
}

@Composable
private fun EmptyScreen() {
    Column(
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier.climateWidgetBackground().semantics { testTag = "EmptyScreen" },
    ) {
        Image(
            provider = ImageProvider(R.drawable.app_icon_launch),
            contentDescription = null,
            modifier = GlanceModifier.padding(bottom = 8.dp).size(HomeAssistantGlanceTheme.dimensions.iconSize),
        )
        Text(
            text = glanceStringResource(commonR.string.widget_climate_no_configuration),
            style = HomeAssistantGlanceTypography.titleSmall.copy(textAlign = TextAlign.Center),
            modifier = GlanceModifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Screen(state: ClimateWidgetDataState, widgetId: Int) {
    Scaffold(
        titleBar = {
            TitleBar(state = state)
        },
        modifier = GlanceModifier.climateWidgetBackground().semantics { testTag = "Screen" },
    ) {
        ClimateContent(state = state, widgetId = widgetId)
    }
}

@Composable
private fun TitleBar(state: ClimateWidgetDataState) {
    Row(
        modifier = GlanceModifier
            .padding(top = 12.dp, end = 12.dp, start = 16.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = state.entityName ?: glanceStringResource(commonR.string.widget_climate_label),
            style = HomeAssistantGlanceTypography.titleLarge,
            maxLines = 1,
            modifier = GlanceModifier.padding(end = 4.dp).defaultWeight(),
        )
        CircleIconButton(
            modifier = GlanceModifier
                .size(HomeAssistantGlanceTheme.dimensions.iconSize)
                .semantics { testTag = "Refresh" },
            contentColor = GlanceTheme.colors.primary,
            imageProvider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = LocalContext.current.getString(commonR.string.widget_climate_refresh),
            backgroundColor = GlanceTheme.colors.widgetBackground,
            onClick = actionRefreshClimate(),
        )
    }
}

@Composable
private fun ClimateContent(state: ClimateWidgetDataState, widgetId: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.currentTemperature != null) {
            Text(
                text = glanceStringResource(
                    commonR.string.widget_climate_current_temperature,
                    formatTemperature(state.currentTemperature, state.temperatureStep),
                    state.temperatureUnit,
                ),
                style = HomeAssistantGlanceTypography.bodyMedium,
                modifier = GlanceModifier.padding(bottom = 4.dp).semantics { testTag = "CurrentTemperature" },
            )
        }

        TargetTemperatureRow(state = state, widgetId = widgetId)

        HvacModeRow(state = state)
    }
}

@Composable
private fun TargetTemperatureRow(state: ClimateWidgetDataState, widgetId: Int) {
    val targetTemp = state.targetTemperature
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        SquareIconButton(
            modifier = GlanceModifier
                .size(HomeAssistantGlanceTheme.dimensions.iconSize)
                .semantics { testTag = "DecreaseTemperature" },
            imageProvider = ImageProvider(R.drawable.ic_minus),
            contentDescription = LocalContext.current.getString(commonR.string.widget_climate_decrease_temperature),
            backgroundColor = GlanceTheme.colors.primary,
            onClick = actionDecreaseTemperature(widgetId = widgetId, step = state.temperatureStep),
        )
        Text(
            text = if (targetTemp != null) {
                glanceStringResource(
                    commonR.string.widget_climate_target_temperature,
                    formatTemperature(targetTemp, state.temperatureStep),
                    state.temperatureUnit,
                )
            } else {
                glanceStringResource(commonR.string.widget_climate_label)
            },
            style = HomeAssistantGlanceTypography.bodyLarge.copy(textAlign = TextAlign.Center),
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 4.dp)
                .semantics { testTag = "TargetTemperature" },
        )
        SquareIconButton(
            modifier = GlanceModifier
                .size(HomeAssistantGlanceTheme.dimensions.iconSize)
                .semantics { testTag = "IncreaseTemperature" },
            imageProvider = ImageProvider(R.drawable.ic_plus),
            contentDescription = LocalContext.current.getString(commonR.string.widget_climate_increase_temperature),
            backgroundColor = GlanceTheme.colors.primary,
            onClick = actionIncreaseTemperature(widgetId = widgetId, step = state.temperatureStep),
        )
    }
}

@Composable
private fun HvacModeRow(state: ClimateWidgetDataState) {
    if (state.hvacMode.isNotEmpty()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = glanceStringResource(commonR.string.widget_climate_mode, state.hvacMode),
                style = HomeAssistantGlanceTypography.bodySmall,
                modifier = GlanceModifier
                    .defaultWeight()
                    .semantics { testTag = "HvacMode" },
            )
            if (state.hvacModes.size > 1) {
                SquareIconButton(
                    modifier = GlanceModifier
                        .size(HomeAssistantGlanceTheme.dimensions.iconSize)
                        .semantics { testTag = "CycleMode" },
                    imageProvider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = LocalContext.current.getString(commonR.string.widget_climate_open),
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    onClick = actionCycleHvacMode(),
                )
            } else {
                SquareIconButton(
                    modifier = GlanceModifier
                        .size(HomeAssistantGlanceTheme.dimensions.iconSize)
                        .semantics { testTag = "OpenInApp" },
                    imageProvider = ImageProvider(R.drawable.ic_widget),
                    contentDescription = LocalContext.current.getString(commonR.string.widget_climate_open),
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    onClick = actionStartWebView("climate?entity_id=${state.entityId}", state.serverId),
                )
            }
        }
    }
}

/**
 * Formats a temperature value to the appropriate number of decimal places based on the step size.
 * For steps smaller than 1 (e.g. 0.5 °C), one decimal place is used; otherwise no decimal places.
 */
private fun formatTemperature(value: Float, step: Float): String {
    return if (abs(step) < 1f) "%.1f".format(value) else "%.0f".format(value)
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(250, 200)
@Composable
private fun ScreenPreview() {
    HomeAssistantGlanceTheme {
        ScreenForState(
            state = ClimateWidgetDataState(
                backgroundType = WidgetBackgroundType.DYNAMICCOLOR,
                textColor = null,
                serverId = 1,
                entityId = "climate.living_room",
                entityName = "Living Room",
                currentTemperature = 21.5f,
                targetTemperature = 22.0f,
                minTemperature = 7f,
                maxTemperature = 35f,
                temperatureUnit = "°C",
                temperatureStep = 0.5f,
                hvacMode = "heat",
                hvacModes = listOf("off", "heat", "cool", "heat_cool"),
            ),
            widgetId = 0,
        )
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview
@Composable
private fun ScreenPreviewEmpty() {
    HomeAssistantGlanceTheme {
        ScreenForState(ClimateWidgetEmptyState)
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview
@Composable
private fun ScreenPreviewLoading() {
    HomeAssistantGlanceTheme {
        ScreenForState(ClimateWidgetLoadingState)
    }
}
