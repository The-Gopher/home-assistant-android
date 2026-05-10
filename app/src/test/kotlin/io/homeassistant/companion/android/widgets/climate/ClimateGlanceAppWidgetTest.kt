package io.homeassistant.companion.android.widgets.climate

import androidx.glance.appwidget.testing.unit.isIndeterminateCircularProgressIndicator
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasNoClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ClimateGlanceAppWidgetTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `Given LoadingState when ScreenForState then it displays CircularProgressIndicator`() = runGlanceAppWidgetUnitTest {
        setContext(context)

        provideComposable {
            ScreenForState(LoadingClimateState)
        }

        onNode(hasTestTag("Screen"))
            .assertDoesNotExist()
        onNode(hasTestTag("EmptyScreen"))
            .assertDoesNotExist()

        onNode(isIndeterminateCircularProgressIndicator())
            .assertExists()
    }

    @Test
    fun `Given EmptyState when ScreenForState then it displays EmptyScreen`() = runGlanceAppWidgetUnitTest {
        setContext(context)

        provideComposable {
            ScreenForState(EmptyClimateState)
        }

        onNode(hasTestTag("LoadingScreen"))
            .assertDoesNotExist()
        onNode(hasTestTag("Screen"))
            .assertDoesNotExist()

        onNode(hasTestTag("EmptyScreen"))
            .assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.widget_no_configuration)))
            .assertExists()
    }

    @Test
    fun `Given data state when ScreenForState then it displays climate data without refresh button`() = runGlanceAppWidgetUnitTest {
        setContext(context)

        provideComposable {
            ScreenForState(
                createClimateState(outOfSync = false),
            )
        }

        onNode(hasTestTag("EmptyScreen"))
            .assertDoesNotExist()
        onNode(hasTestTag("LoadingScreen"))
            .assertDoesNotExist()

        onNode(hasTestTag("Screen"))
            .assertExists()
        onNode(hasTextEqualTo("Living Room"))
            .assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.widget_climate_mode)))
            .assertExists()
        onNode(hasTextEqualTo("Heat"))
            .assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.widget_climate_current_temperature)))
            .assertExists()
        onNode(hasTextEqualTo("20.5 °C"))
            .assertExists()
        onNode(hasTextEqualTo(context.getString(R.string.widget_climate_target_temperature)))
            .assertExists()
        onNode(hasTextEqualTo("22.0 °C"))
            .assertExists()
        onNode(hasTestTag("OutOfSync"))
            .assertDoesNotExist()
        onNode(hasTestTag("Refresh"))
            .assertDoesNotExist()
    }

    @Test
    fun `Given out of sync data state when ScreenForState then it displays cannot sync icon`() = runGlanceAppWidgetUnitTest {
        setContext(context)

        provideComposable {
            ScreenForState(
                createClimateState(outOfSync = true),
            )
        }

        onNode(hasTestTag("OutOfSync"))
            .assertExists()
            .assertHasNoClickAction()
        onNode(hasContentDescriptionEqualTo(context.getString(R.string.widget_entity_fetch_error)))
            .assertExists()
        onNode(hasTestTag("Refresh"))
            .assertDoesNotExist()
    }

    @Test
    fun `Given multi word HVAC mode when ScreenForState then it displays formatted mode`() = runGlanceAppWidgetUnitTest {
        setContext(context)

        provideComposable {
            ScreenForState(
                createClimateState(hvacMode = "heat_cool"),
            )
        }

        onNode(hasTextEqualTo("Heat Cool"))
            .assertExists()
    }

    private fun createClimateState(
        outOfSync: Boolean = false,
        hvacMode: String = "heat",
    ): ClimateStateWithData {
        return ClimateStateWithData(
            backgroundType = WidgetBackgroundType.DYNAMICCOLOR,
            textColor = null,
            serverId = 1,
            entityId = "climate.living_room",
            entityName = "Living Room",
            hvacMode = hvacMode,
            currentTemperature = "20.5",
            targetTemperature = "22.0",
            temperatureUnit = "°C",
            outOfSync = outOfSync,
        )
    }
}
