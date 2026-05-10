package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback

/**
 * Returns a Glance [Action] that triggers a refresh of the climate widget.
 */
@Composable
internal fun actionRefreshClimate(): Action = actionRunCallback<RefreshClimateAction>()

/**
 * Triggers a full update of the climate widget by calling [ClimateGlanceAppWidget.update].
 *
 * Note: This class must be public because it is instantiated by the Glance framework.
 */
class RefreshClimateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ClimateGlanceAppWidget().update(context, glanceId)
    }
}
