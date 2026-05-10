package io.homeassistant.companion.android.widgets.climate

import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.match
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class ClimateWidgetActionsTest {

    private val entryPoints = object : AdjustTargetTemperatureAction.AdjustTargetTemperatureActionEntryPoint {
        val dao = mockk<ClimateWidgetDao>()
        val integrationRepository = mockk<IntegrationRepository>()
        val serverManager = mockk<ServerManager>().apply {
            coEvery { integrationRepository(any()) } returns integrationRepository
        }

        override fun climateServerManager(): ServerManager = serverManager

        override fun climateWidgetDao(): ClimateWidgetDao = dao
    }

    private data class FakeGlanceId(val id: Int) : GlanceId

    @Test
    fun `Given unknown widget id when invoking adjust temperature action then it does nothing`() = runTest {
        val action = spyk<AdjustTargetTemperatureAction>()
        val glanceManager = mockk<GlanceAppWidgetManager>()
        val widgetId = 1

        every { action.getEntryPoints(any()) } returns entryPoints
        every { action.getGlanceManager(any()) } returns glanceManager
        every { glanceManager.getAppWidgetId(any()) } returns widgetId
        coEvery { entryPoints.dao.get(widgetId) } returns null

        action.onAction(
            context = mockk(),
            glanceId = FakeGlanceId(widgetId),
            parameters = actionParametersOf(CLIMATE_TEMP_DIRECTION_KEY to 1),
        )

        coVerify(exactly = 1) { entryPoints.dao.get(widgetId) }
        coVerify(exactly = 0) { entryPoints.serverManager.integrationRepository(any()) }
    }

    @Test
    fun `Given missing target temperature when invoking adjust temperature action then it does nothing`() = runTest {
        val action = spyk<AdjustTargetTemperatureAction>()
        val glanceManager = mockk<GlanceAppWidgetManager>()
        val widgetId = 1
        val widgetEntity = createWidgetEntity(widgetId).copy(targetTemperature = null)

        every { action.getEntryPoints(any()) } returns entryPoints
        every { action.getGlanceManager(any()) } returns glanceManager
        every { glanceManager.getAppWidgetId(any()) } returns widgetId
        coEvery { entryPoints.dao.get(widgetId) } returns widgetEntity
        coEvery { entryPoints.serverManager.getServer(widgetEntity.serverId) } returns mockk()

        action.onAction(
            context = mockk(),
            glanceId = FakeGlanceId(widgetId),
            parameters = actionParametersOf(CLIMATE_TEMP_DIRECTION_KEY to -1),
        )

        coVerify(exactly = 1) { entryPoints.dao.get(widgetId) }
        coVerify(exactly = 0) { entryPoints.integrationRepository.callAction(any(), any(), any()) }
    }

    @Test
    fun `Given target temperature and celsius unit when increasing then it calls set temperature with half degree step`() = runTest {
        val action = spyk<AdjustTargetTemperatureAction>()
        val glanceManager = mockk<GlanceAppWidgetManager>()
        val widgetId = 1
        val widgetEntity = createWidgetEntity(widgetId).copy(
            temperatureUnit = "°C",
            targetTemperature = "22.0",
        )

        every { action.getEntryPoints(any()) } returns entryPoints
        every { action.getGlanceManager(any()) } returns glanceManager
        every { glanceManager.getAppWidgetId(any()) } returns widgetId
        coEvery { entryPoints.dao.get(widgetId) } returns widgetEntity
        coEvery { entryPoints.serverManager.getServer(widgetEntity.serverId) } returns mockk()
        coEvery { entryPoints.integrationRepository.callAction(any(), any(), any()) } returns Unit
        coEvery {
            entryPoints.dao.updateWidgetCachedState(
                widgetId = widgetId,
                entityName = widgetEntity.entityName,
                hvacMode = widgetEntity.hvacMode,
                currentTemperature = widgetEntity.currentTemperature,
                targetTemperature = "22.5",
                temperatureUnit = widgetEntity.temperatureUnit,
            )
        } returns Unit

        // Validate the service call and cache update; update() then throws for fake glance IDs.
        try {
            action.onAction(
                context = mockk(),
                glanceId = FakeGlanceId(widgetId),
                parameters = actionParametersOf(CLIMATE_TEMP_DIRECTION_KEY to 1),
            )
            fail { "onAction should fail with invalid glance ID" }
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid Glance ID", e.message)
        }

        coVerify(exactly = 1) {
            entryPoints.integrationRepository.callAction(
                domain = "climate",
                action = "set_temperature",
                actionData = match {
                    it["entity_id"] == "climate.living_room" && it["temperature"] == 22.5
                },
            )
        }
        coVerify(exactly = 1) {
            entryPoints.dao.updateWidgetCachedState(
                widgetId = widgetId,
                entityName = widgetEntity.entityName,
                hvacMode = widgetEntity.hvacMode,
                currentTemperature = widgetEntity.currentTemperature,
                targetTemperature = "22.5",
                temperatureUnit = widgetEntity.temperatureUnit,
            )
        }
    }

    @Test
    fun `Given fahrenheit unit when getting step size then use one degree increment`() {
        val action = AdjustTargetTemperatureAction()
        assertEquals(1.0, action.getStepSize("°F"))
        assertEquals(0.5, action.getStepSize("°C"))
        assertEquals(0.5, action.getStepSize(null))
    }

    private fun createWidgetEntity(widgetId: Int): ClimateWidgetEntity {
        return ClimateWidgetEntity(
            id = widgetId,
            serverId = 1,
            entityId = "climate.living_room",
            backgroundType = WidgetBackgroundType.DAYNIGHT,
            textColor = null,
            entityName = "Living Room",
            label = "Living Room",
            hvacMode = "heat",
            currentTemperature = "20.0",
            targetTemperature = "22.0",
            temperatureUnit = "°C",
        )
    }
}
