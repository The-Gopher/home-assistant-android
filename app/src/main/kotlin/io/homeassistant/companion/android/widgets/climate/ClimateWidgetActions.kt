package io.homeassistant.companion.android.widgets.climate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import io.homeassistant.companion.android.widgets.todo.RefreshAction
import io.homeassistant.companion.android.widgets.todo.actionRefreshTodo


/**
 * Get an Action that will refresh the Todo widget once given to Glance.
 */
@Composable
internal fun actionRefreshClimate(): Action {
    return actionRunCallback<RefreshAction>()
}
/**
 * Basic action that will refresh the given widget. Use [actionRefreshTodo] to get the
 * Action for Glance.
 *
 * Note: This needs to be public since it is instantiated by the Glance framework.
 *
 * From the doc https://developer.android.com/design/ui/mobile/guides/widgets/widget_quality_guide#tier2-content:
 * > Widget must let users manually refresh content, if there is an expectation the data refreshes more frequently than the UI.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ClimateGlanceAppWidget().update(context, glanceId)
    }
}
