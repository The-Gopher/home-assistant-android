package io.homeassistant.companion.android.database.widget

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import io.homeassistant.companion.android.database.widget.converters.ClimateLastUpdateDataConverter
import kotlinx.serialization.Serializable

@TypeConverters(ClimateLastUpdateDataConverter::class)
@Entity(tableName = "climate_widget")
data class ClimateWidgetEntity(
    @PrimaryKey
    override val id: Int,
    @ColumnInfo(name = "server_id", defaultValue = "0")
    override val serverId: Int,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "background_type", defaultValue = "DAYNIGHT")
    override val backgroundType: WidgetBackgroundType = WidgetBackgroundType.DAYNIGHT,
    @ColumnInfo(name = "text_color")
    override val textColor: String? = null,
    @ColumnInfo(name = "latest_update_data")
    val latestUpdateData: LastUpdateData? = null,
) : WidgetEntity<ClimateWidgetEntity>,
    ThemeableWidgetEntity {

    /**
     * Stores the last known state of the climate entity so that the widget can render
     * even when the server is temporarily unreachable.
     */
    @Serializable
    data class LastUpdateData(
        val entityName: String? = null,
        val currentTemperature: Float? = null,
        val targetTemperature: Float? = null,
        val minTemperature: Float = 0f,
        val maxTemperature: Float = 100f,
        val temperatureUnit: String = "",
        val temperatureStep: Float = 1f,
        val hvacMode: String = "",
        val hvacModes: List<String> = emptyList(),
    ) : java.io.Serializable

    override fun copyWithWidgetId(appWidgetId: Int): ClimateWidgetEntity = copy(id = appWidgetId)
}
