package io.homeassistant.companion.android.database.widget

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClimateWidgetDao : WidgetDao<ClimateWidgetEntity> {

    @Query("SELECT * FROM climate_widget WHERE id = :id")
    suspend fun get(id: Int): ClimateWidgetEntity?

    @Query("SELECT * FROM climate_widget WHERE id = :id")
    fun getFlow(id: Int): Flow<ClimateWidgetEntity?>

    @Query("SELECT * FROM climate_widget")
    suspend fun getAll(): List<ClimateWidgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun add(entity: ClimateWidgetEntity)

    @Query("DELETE FROM climate_widget WHERE id = :id")
    override suspend fun delete(id: Int)

    @Query("DELETE FROM climate_widget WHERE id IN (:ids)")
    override suspend fun deleteAll(ids: IntArray)

    @Query("SELECT COUNT(*) FROM climate_widget")
    override fun getWidgetCountFlow(): Flow<Int>

    /**
     * Updates the cached state fields on an existing climate widget entry.
     *
     * This is called after fetching a fresh entity update from the server so that
     * the widget can show meaningful data while it is out of composition.
     */
    @Query(
        "UPDATE climate_widget SET entity_name = :entityName, hvac_mode = :hvacMode, " +
            "current_temperature = :currentTemperature, target_temperature = :targetTemperature, " +
            "temperature_unit = :temperatureUnit WHERE id = :widgetId",
    )
    suspend fun updateWidgetCachedState(
        widgetId: Int,
        entityName: String?,
        hvacMode: String?,
        currentTemperature: String?,
        targetTemperature: String?,
        temperatureUnit: String?,
    )
}
