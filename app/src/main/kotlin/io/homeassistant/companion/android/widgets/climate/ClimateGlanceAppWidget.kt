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
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.Scaffold
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
import io.homeassistant.companion.android.util.compose.glanceStringResource
import io.homeassistant.companion.android.widgets.climate.ClimateWidgetState.Companion.getColors

/**
 * Glance widget that displays the current state of a Home Assistant climate entity.
 *
 * Shows the entity name, current HVAC mode, measured temperature, and set-point
 * temperature. When live sync fails, it shows the last cached values with a
 * cannot-sync indicator.
 *
 * The widget's climate entity and theme are configured via [ClimateWidgetConfigureActivity].
 */
class ClimateGlanceAppWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ClimateGlanceWidgetEntryPoint {
        fun climateStateUpdater(): ClimateWidgetStateUpdater
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = GlanceAppWidgetManager(context)
        val widgetId = manager.getAppWidgetId(id)

        provideContent {
            val entryPoints = remember { EntryPoints.get(context, ClimateGlanceWidgetEntryPoint::class.java) }
            val flow = remember { entryPoints.climateStateUpdater().stateFlow(widgetId) }
            val state by flow.collectAsState(LoadingClimateState)

            HomeAssistantGlanceTheme(colors = state.getColors()) {
                ScreenForState(state)
            }
        }
    }
}

@Composable
private fun GlanceModifier.climateWidgetBackground(): GlanceModifier =
    this.appWidgetBackground().fillMaxSize().background(GlanceTheme.colors.widgetBackground)

@Composable
@VisibleForTesting
internal fun ScreenForState(state: ClimateWidgetState) {
    when (state) {
        LoadingClimateState -> LoadingScreen()
        EmptyClimateState -> EmptyScreen()
        is ClimateStateWithData -> Screen(state)
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
            text = glanceStringResource(commonR.string.widget_no_configuration),
            style = HomeAssistantGlanceTypography.titleSmall.copy(textAlign = TextAlign.Center),
            modifier = GlanceModifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Screen(state: ClimateStateWithData) {
    Scaffold(
        titleBar = {
            TitleBar(
                entityName = state.entityName,
                entityId = state.entityId,
                outOfSync = state.outOfSync,
            )
        },
        modifier = GlanceModifier.climateWidgetBackground().semantics { testTag = "Screen" },
    ) {
        ClimateContent(state)
    }
}

@Composable
private fun TitleBar(entityName: String?, entityId: String, outOfSync: Boolean) {
    Row(
        modifier = GlanceModifier.padding(top = 12.dp, end = 12.dp, start = 16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = entityName ?: entityId,
            style = HomeAssistantGlanceTypography.titleLarge,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().padding(end = 4.dp),
        )
        if (outOfSync) {
            Image(
                provider = ImageProvider(R.drawable.ic_sync_problem),
                contentDescription = glanceStringResource(commonR.string.widget_entity_fetch_error),
                modifier = GlanceModifier.size(16.dp).semantics { testTag = "OutOfSync" },
            )
        }
    }
}

@Composable
private fun ClimateContent(state: ClimateStateWithData) {
    Column(
        modifier = GlanceModifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        HvacModeRow(state.hvacMode)
        TemperatureRow(
            labelRes = commonR.string.widget_climate_current_temperature,
            value = state.currentTemperature,
            unit = state.temperatureUnit,
        )
        TemperatureRow(
            labelRes = commonR.string.widget_climate_target_temperature,
            value = state.targetTemperature,
            unit = state.temperatureUnit,
        )
    }
}

@Composable
private fun HvacModeRow(hvacMode: String?) {
    if (hvacMode == null) return
    Row(
        modifier = GlanceModifier.padding(vertical = 4.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = glanceStringResource(commonR.string.widget_climate_mode),
            style = HomeAssistantGlanceTypography.bodySmall,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = hvacMode.formatHvacMode(),
            style = HomeAssistantGlanceTypography.bodySmall,
        )
    }
}

private fun String.formatHvacMode(): String =
    split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

@Composable
private fun TemperatureRow(labelRes: Int, value: String?, unit: String?) {
    if (value == null) return
    Row(
        modifier = GlanceModifier.padding(vertical = 4.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = glanceStringResource(labelRes),
            style = HomeAssistantGlanceTypography.bodySmall,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = if (unit != null) "$value $unit" else value,
            style = HomeAssistantGlanceTypography.bodySmall,
        )
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(250, 200)
@Composable
private fun ScreenPreview() {
    HomeAssistantGlanceTheme {
        ScreenForState(
            ClimateStateWithData(
                backgroundType = WidgetBackgroundType.DYNAMICCOLOR,
                textColor = null,
                serverId = 1,
                entityId = "climate.living_room",
                entityName = "Living Room",
                hvacMode = "heat",
                currentTemperature = "20.5",
                targetTemperature = "22.0",
                temperatureUnit = "°C",
                outOfSync = true,
            ),
        )
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(250, 200)
@Composable
private fun ScreenPreviewEmpty() {
    HomeAssistantGlanceTheme {
        ScreenForState(EmptyClimateState)
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(250, 200)
@Composable
private fun ScreenPreviewLoading() {
    HomeAssistantGlanceTheme {
        ScreenForState(LoadingClimateState)
    }
}
