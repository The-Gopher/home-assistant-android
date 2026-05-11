package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.database.widget.ClimateWidgetDao
import io.homeassistant.companion.android.database.widget.ClimateWidgetEntity
import io.homeassistant.companion.android.widgets.BaseGlanceEntityWidgetReceiver
import io.homeassistant.companion.android.widgets.EntitiesPerServer

/**
 * Receiver for the Climate Glance Widget.
 *
 * Manages lifecycle events and entity updates for the climate widget. Maps widget IDs to their
 * associated entities and integrates with the database via [ClimateWidgetDao].
 *
 * Note: Register this receiver in the manifest and in the Application class; otherwise widgets
 * will not update after the composition ends.
 */
@AndroidEntryPoint
class ClimateWidget : BaseGlanceEntityWidgetReceiver<ClimateWidgetEntity, ClimateWidgetDao>() {
    override val glanceAppWidget: GlanceAppWidget = ClimateGlanceAppWidget()

    override suspend fun getWidgetEntitiesByServer(context: Context): Map<Int, EntitiesPerServer> {
        return dao.getAll()
            .associate { widget -> widget.id to EntitiesPerServer(widget.serverId, listOf(widget.entityId)) }
    }
}
