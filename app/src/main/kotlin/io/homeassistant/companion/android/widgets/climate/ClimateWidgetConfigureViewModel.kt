package io.homeassistant.companion.android.widgets.climate

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.color.DynamicColors
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationDomains.CLIMATE_DOMAIN
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.DeviceRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.widgets.ACTION_APPWIDGET_CREATED
import io.homeassistant.companion.android.widgets.EXTRA_WIDGET_ENTITY
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@HiltViewModel(assistedFactory = ClimateWidgetConfigureViewModel.Factory::class)
class ClimateWidgetConfigureViewModel @AssistedInject constructor(
    private val climateWidgetDao: ClimateWidgetDao,
    private val serverManager: ServerManager,
    @Assisted preSelectedEntityId: String?,
) : ViewModel() {
    private var supportedTextColors: List<String> = emptyList()
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    val servers = serverManager.serversFlow
    var selectedServerId by mutableIntStateOf(ServerManager.SERVER_ID_ACTIVE)
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    val entities: StateFlow<List<Entity>> = snapshotFlow { selectedServerId }
        .distinctUntilChanged()
        .mapLatest { serverId ->
            if (serverManager.isRegistered()) {
                try {
                    serverManager.integrationRepository(serverId)
                        .getEntities()
                        .orEmpty()
                        .filter { entity -> entity.domain == CLIMATE_DOMAIN }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get climate entities")
                    emptyList()
                }
            } else {
                Timber.w("No server registered")
                emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500.milliseconds), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val entityRegistry: StateFlow<List<EntityRegistryResponse>?> = snapshotFlow { selectedServerId }
        .distinctUntilChanged()
        .mapLatest { serverId ->
            if (serverManager.isRegistered()) {
                try {
                    serverManager.webSocketRepository(serverId).getEntityRegistry()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get entity registry")
                    null
                }
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500.milliseconds), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val deviceRegistry: StateFlow<List<DeviceRegistryResponse>?> = snapshotFlow { selectedServerId }
        .distinctUntilChanged()
        .mapLatest { serverId ->
            if (serverManager.isRegistered()) {
                try {
                    serverManager.webSocketRepository(serverId).getDeviceRegistry()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get device registry")
                    null
                }
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500.milliseconds), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val areaRegistry: StateFlow<List<AreaRegistryResponse>?> = snapshotFlow { selectedServerId }
        .distinctUntilChanged()
        .mapLatest { serverId ->
            if (serverManager.isRegistered()) {
                try {
                    serverManager.webSocketRepository(serverId).getAreaRegistry()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get area registry")
                    null
                }
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500.milliseconds), null)

    private val selectedEntityMutex = Mutex()
    var selectedEntityId by mutableStateOf<String?>(preSelectedEntityId)
    var selectedBackgroundType by mutableStateOf(
        if (DynamicColors.isDynamicColorAvailable()) {
            WidgetBackgroundType.DYNAMICCOLOR
        } else {
            WidgetBackgroundType.DAYNIGHT
        },
    )
    var textColorIndex by mutableIntStateOf(0)
    var isUpdateWidget by mutableStateOf(false)

    init {
        viewModelScope.launch {
            entities.collect { entities ->
                selectedEntityMutex.withLock {
                    if (selectedEntityId == null) {
                        selectedEntityId = entities.firstOrNull()?.entityId
                    }
                }
            }
        }
    }

    /**
     * Initialises the view model with the given widget ID and the list of text colors supported by
     * the app theme. Must be called once from the Activity before the user can interact.
     */
    fun onSetup(widgetId: Int, supportedTextColors: List<String>) {
        this.supportedTextColors = supportedTextColors
        maybeLoadPreviousState(widgetId)
        this.widgetId = widgetId
    }

    private fun maybeLoadPreviousState(widgetId: Int) = viewModelScope.launch {
        selectedEntityMutex.withLock {
            if (this@ClimateWidgetConfigureViewModel.widgetId == AppWidgetManager.INVALID_APPWIDGET_ID &&
                selectedEntityId == null
            ) {
                climateWidgetDao.get(widgetId)?.let {
                    isUpdateWidget = true
                    selectedServerId = it.serverId
                    selectedEntityId = it.entityId
                    selectedBackgroundType = it.backgroundType
                    val colorIndex = supportedTextColors.indexOf(it.textColor)
                    textColorIndex = if (colorIndex == -1) 0 else colorIndex
                }
            }
        }
    }

    /** Updates the currently selected server. Resets the selected entity when the server changes. */
    fun setServer(serverId: Int) {
        if (selectedServerId == serverId) return
        selectedServerId = serverId
        viewModelScope.launch { selectedEntityMutex.withLock { selectedEntityId = null } }
    }

    /** Returns true if the current server and entity selections are valid. */
    suspend fun isValidSelection(): Boolean {
        selectedEntityMutex.withLock {
            return serverManager.getServer(selectedServerId) != null &&
                selectedEntityId in entities.value.map { it.entityId }
        }
    }

    /** Persists the widget configuration to the database. */
    suspend fun updateWidgetConfiguration() {
        if (!isValidSelection()) {
            Timber.d("Widget data is invalid")
            throw IllegalArgumentException("Widget data is invalid")
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Timber.w("Widget ID is invalid")
            throw IllegalArgumentException("Widget ID is invalid")
        }
        climateWidgetDao.add(getPendingDaoEntity())
    }

    /**
     * Returns a [ClimateWidgetEntity] reflecting the current configuration without saving it to the
     * database.
     */
    private suspend fun getPendingDaoEntity(): ClimateWidgetEntity {
        val textColor = if (selectedBackgroundType == WidgetBackgroundType.TRANSPARENT) {
            supportedTextColors.getOrNull(textColorIndex) ?: supportedTextColors.first()
        } else {
            ""
        }
        selectedEntityMutex.withLock {
            val entityId = selectedEntityId!!
            val entity = serverManager.integrationRepository(selectedServerId).getEntity(entityId)
            val entityName = entity?.attributes?.get("friendly_name") as? String
            val currentTemp = (entity?.attributes?.get("current_temperature") as? Number)?.toFloat()
            val targetTemp = (entity?.attributes?.get("temperature") as? Number)?.toFloat()
            val minTemp = (entity?.attributes?.get("min_temp") as? Number)?.toFloat() ?: 0f
            val maxTemp = (entity?.attributes?.get("max_temp") as? Number)?.toFloat() ?: 100f
            val tempUnit = entity?.attributes?.get("temperature_unit") as? String ?: ""
            val tempStep = (entity?.attributes?.get("target_temp_step") as? Number)?.toFloat()
                ?: if (tempUnit == "°C") 0.5f else 1f
            @Suppress("UNCHECKED_CAST")
            val hvacModes = (entity?.attributes?.get("hvac_modes") as? List<String>) ?: emptyList()
            val hvacMode = entity?.state ?: ""

            return ClimateWidgetEntity(
                id = widgetId,
                serverId = selectedServerId,
                entityId = entityId,
                backgroundType = selectedBackgroundType,
                textColor = textColor,
                latestUpdateData = ClimateWidgetEntity.LastUpdateData(
                    entityName = entityName,
                    currentTemperature = currentTemp,
                    targetTemperature = targetTemp?.coerceIn(minTemp, maxTemp),
                    minTemperature = minTemp,
                    maxTemperature = maxTemp,
                    temperatureUnit = tempUnit,
                    temperatureStep = tempStep,
                    hvacMode = hvacMode,
                    hvacModes = hvacModes,
                ),
            )
        }
    }

    /**
     * Requests the widget to be pinned to the launcher and waits until the system confirms it has
     * been created. The success callback injects the new [ClimateWidgetEntity] into the DAO.
     *
     * **Warning**: If the user cancels widget placement this function will not return. Calling the
     * function multiple times while both are pending will cause both to return when the next widget
     * is created.
     */
    suspend fun requestWidgetCreation(context: Context) {
        climateWidgetDao.getWidgetCountFlow().drop(1).onStart {
            GlanceAppWidgetManager(context)
                .requestPinGlanceAppWidget(
                    ClimateWidget::class.java,
                    successCallback = PendingIntent.getBroadcast(
                        context,
                        System.currentTimeMillis().toInt(),
                        Intent(context, ClimateWidget::class.java).apply {
                            action = ACTION_APPWIDGET_CREATED
                            putExtra(EXTRA_WIDGET_ENTITY, getPendingDaoEntity())
                        },
                        PendingIntent.FLAG_MUTABLE,
                    ),
                )
        }.first()
    }

    /** Triggers an immediate UI update for the widget. */
    fun updateWidget(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val glanceId = GlanceAppWidgetManager(appContext).getGlanceIdBy(widgetId)
            ClimateGlanceAppWidget().update(appContext, glanceId)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(preSelectedEntityId: String?): ClimateWidgetConfigureViewModel
    }
}
