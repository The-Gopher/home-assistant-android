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
import io.homeassistant.companion.android.common.data.integration.friendlyName
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AreaRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.DeviceRegistryResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.EntityRegistryResponse
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.database.widget.WidgetBackgroundType
import io.homeassistant.companion.android.widgets.ACTION_APPWIDGET_CREATED
import io.homeassistant.companion.android.widgets.EXTRA_WIDGET_ENTITY
import kotlin.time.Clock
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
    private val clock: Clock,
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
                        .filter { it.domain == CLIMATE_DOMAIN }
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
            entities.collect { entityList ->
                selectedEntityMutex.withLock {
                    if (selectedEntityId == null) {
                        selectedEntityId = entityList.firstOrNull()?.entityId
                    }
                }
            }
        }
    }

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

    fun setServer(serverId: Int) {
        if (selectedServerId == serverId) return
        selectedServerId = serverId
        viewModelScope.launch { selectedEntityMutex.withLock { selectedEntityId = null } }
    }

    suspend fun isValidSelection(): Boolean {
        selectedEntityMutex.withLock {
            return serverManager.getServer(selectedServerId) != null &&
                selectedEntityId in entities.value.map { it.entityId }
        }
    }

    suspend fun updateWidgetConfiguration() {
        if (!isValidSelection()) {
            Timber.d("Climate widget data is invalid")
            throw IllegalArgumentException("Climate widget data is invalid")
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Timber.w("Widget ID is invalid")
            throw IllegalArgumentException("Widget ID is invalid")
        }
        climateWidgetDao.add(getPendingDaoEntity())
    }

    /**
     * Returns a [ClimateWidgetEntity] with the current selection without pushing it to the DAO.
     * The cached state fields are pre-populated by fetching the entity from the server.
     */
    private suspend fun getPendingDaoEntity(): ClimateWidgetEntity {
        val textColor = if (selectedBackgroundType == WidgetBackgroundType.TRANSPARENT) {
            supportedTextColors.getOrNull(textColorIndex) ?: supportedTextColors.firstOrNull() ?: ""
        } else {
            ""
        }
        selectedEntityMutex.withLock {
            val entityId = selectedEntityId!!
            val entity = runCatching {
                serverManager.integrationRepository(selectedServerId).getEntity(entityId)
            }.getOrNull()

            val attrs = entity?.attributes as? Map<*, *> ?: emptyMap<String, Any>()
            return ClimateWidgetEntity(
                id = widgetId,
                serverId = selectedServerId,
                entityId = entityId,
                backgroundType = selectedBackgroundType,
                textColor = textColor,
                entityName = entity?.friendlyName,
                hvacMode = entity?.state,
                currentTemperature = attrs["current_temperature"]?.toString(),
                targetTemperature = attrs["temperature"]?.toString(),
                temperatureUnit = attrs["unit_of_measurement"]?.toString(),
            )
        }
    }

    /**
     * Requests the widget to be pinned to the home screen and waits until it has been saved to the
     * DAO before returning.
     *
     * Note: If the user cancels the pin request this function will not return. This matches the
     * behaviour of [io.homeassistant.companion.android.widgets.todo.TodoWidgetConfigureViewModel].
     */
    suspend fun requestWidgetCreation(context: Context) {
        climateWidgetDao.getWidgetCountFlow().drop(1).onStart {
            GlanceAppWidgetManager(context)
                .requestPinGlanceAppWidget(
                    ClimateWidget::class.java,
                    successCallback = PendingIntent.getBroadcast(
                        context,
                        clock.now().toEpochMilliseconds().toInt(),
                        Intent(context, ClimateWidget::class.java).apply {
                            action = ACTION_APPWIDGET_CREATED
                            putExtra(EXTRA_WIDGET_ENTITY, getPendingDaoEntity())
                        },
                        // Mutable so the system can inject EXTRA_APPWIDGET_ID
                        PendingIntent.FLAG_MUTABLE,
                    ),
                )
        }.first()
    }

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
