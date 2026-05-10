package io.homeassistant.companion.android.widgets.climate

import app.cash.turbine.test
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDateTime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClimateWidgetStateUpdaterTest {

    private val dao = mockk<ClimateWidgetDao>()
    private val integrationRepository = mockk<IntegrationRepository>()
    private val serverManager = mockk<ServerManager>().apply {
        coEvery { integrationRepository(any()) } returns integrationRepository
    }
    private val updater = ClimateWidgetStateUpdater(dao, serverManager)

    @Test
    fun `Given widgetId in DAO with removed server when subscribing to stateFlow then emits cached state and out of sync state`() = runTest {
        val widgetId = 42
        val widgetEntity = createWidgetEntity(widgetId = widgetId)

        coEvery { dao.getFlow(widgetId) } returns channelFlow {
            send(widgetEntity)
            awaitClose()
        }
        coEvery { dao.get(widgetId) } returns widgetEntity
        coEvery { serverManager.getServer(widgetEntity.serverId) } returns null

        updater.stateFlow(widgetId).test {
            assertEquals(widgetEntity.toStateWithData(), awaitItem())
            assertEquals(widgetEntity.toStateWithData(outOfSync = true), awaitItem())
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given widgetId in DAO with sync failure when subscribing to stateFlow then emits cached state and out of sync state`() = runTest {
        val widgetId = 42
        val widgetEntity = createWidgetEntity(widgetId = widgetId)

        coEvery { dao.getFlow(widgetId) } returns channelFlow {
            send(widgetEntity)
            awaitClose()
        }
        coEvery { dao.get(widgetId) } returns widgetEntity
        coEvery { serverManager.getServer(widgetEntity.serverId) } returns mockk()
        coEvery { integrationRepository.getEntity(widgetEntity.entityId) } throws IllegalStateException("boom")

        updater.stateFlow(widgetId).test {
            assertEquals(widgetEntity.toStateWithData(), awaitItem())
            assertEquals(widgetEntity.toStateWithData(outOfSync = true), awaitItem())
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given widgetId in DAO with live entity when subscribing to stateFlow then emits fresh state and updates cache`() = runTest {
        val widgetId = 42
        val widgetEntity = createWidgetEntity(widgetId = widgetId)
        val entity = createServerEntity(entityId = widgetEntity.entityId)

        coEvery { dao.getFlow(widgetId) } returns channelFlow {
            send(widgetEntity)
            awaitClose()
        }
        coEvery { dao.get(widgetId) } returns widgetEntity
        coEvery { serverManager.getServer(widgetEntity.serverId) } returns mockk()
        coEvery { integrationRepository.getEntity(widgetEntity.entityId) } returns entity
        coEvery { integrationRepository.getEntityUpdates(listOf(widgetEntity.entityId)) } returns channelFlow {
            awaitClose()
        }
        coJustRun {
            dao.updateWidgetCachedState(
                widgetId = widgetId,
                entityName = "Living Room",
                hvacMode = "heat",
                currentTemperature = "20.5",
                targetTemperature = "22.0",
                temperatureUnit = "°C",
            )
        }

        updater.stateFlow(widgetId).test {
            assertEquals(widgetEntity.toStateWithData(), awaitItem())
            assertEquals(
                widgetEntity.toStateWithData(
                    label = "Living Room",
                    hvacMode = "heat",
                    currentTemperature = "20.5",
                    targetTemperature = "22.0",
                    temperatureUnit = "°C",
                ),
                awaitItem(),
            )
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }

        coVerify(exactly = 1) {
            dao.updateWidgetCachedState(
                widgetId = widgetId,
                entityName = "Living Room",
                hvacMode = "heat",
                currentTemperature = "20.5",
                targetTemperature = "22.0",
                temperatureUnit = "°C",
            )
        }
    }

    private fun createWidgetEntity(widgetId: Int): ClimateWidgetEntity {
        return ClimateWidgetEntity(
            id = widgetId,
            serverId = 1,
            entityId = "climate.living_room",
            backgroundType = WidgetBackgroundType.DAYNIGHT,
            textColor = null,
            entityName = "Cached Living Room",
            hvacMode = "off",
            currentTemperature = "19.0",
            targetTemperature = "21.0",
            temperatureUnit = "°C",
        )
    }

    private fun createServerEntity(entityId: String): Entity {
        return Entity(
            entityId = entityId,
            state = "heat",
            attributes = mapOf(
                "friendly_name" to "Living Room",
                "current_temperature" to 20.5,
                "temperature" to 22.0,
                "unit_of_measurement" to "°C",
            ),
            lastChanged = LocalDateTime.now(),
            lastUpdated = LocalDateTime.now(),
        )
    }

    private fun ClimateWidgetEntity.toStateWithData(
        outOfSync: Boolean = false,
        label: String = this.label ?: this.entityName ?: this.entityId,
        hvacMode: String? = this.hvacMode,
        currentTemperature: String? = this.currentTemperature,
        targetTemperature: String? = this.targetTemperature,
        temperatureUnit: String? = this.temperatureUnit,
    ): ClimateStateWithData {
        return ClimateStateWithData(
            backgroundType = backgroundType,
            textColor = textColor,
            serverId = serverId,
            entityId = entityId,
            label = label,
            hvacMode = hvacMode,
            currentTemperature = currentTemperature,
            targetTemperature = targetTemperature,
            temperatureUnit = temperatureUnit,
            outOfSync = outOfSync,
        )
    }
}
