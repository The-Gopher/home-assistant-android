package io.homeassistant.companion.android.database.widget

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the persisted configuration of a climate widget.
 *
 * Stores the entity ID, server ID, and display preferences (background type, text color)
 * along with cached state data to show when the widget is not in composition.
 */
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
    @ColumnInfo(name = "entity_name")
    val entityName: String? = null,
    @ColumnInfo(name = "hvac_mode")
    val hvacMode: String? = null,
    @ColumnInfo(name = "current_temperature")
    val currentTemperature: String? = null,
    @ColumnInfo(name = "target_temperature")
    val targetTemperature: String? = null,
    @ColumnInfo(name = "temperature_unit")
    val temperatureUnit: String? = null,
) : WidgetEntity<ClimateWidgetEntity>,
    ThemeableWidgetEntity {

    override fun copyWithWidgetId(appWidgetId: Int): ClimateWidgetEntity = copy(id = appWidgetId)
}
