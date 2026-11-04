package com.dmeltz.personalworkout.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dmeltz.personalworkout.MainActivity
import com.dmeltz.personalworkout.R
import com.dmeltz.personalworkout.Repository
import com.dmeltz.personalworkout.currentMood
import com.dmeltz.personalworkout.today
import com.dmeltz.personalworkout.ui.pipDrawable

/** 2x1 home screen widget: Pip (angrier as the day goes by without practice), streak and average grade. */
class StreakWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StreakWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val state = Repository.get(context).state
            val day = today()
            val streak = state.streak(day)
            val grade = state.averageGrade(day)
            val mood = currentMood(state)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_streak).apply {
                for (id in intArrayOf(R.id.widget_pip_1, R.id.widget_pip_2, R.id.widget_pip_3)) {
                    setImageViewResource(id, pipDrawable(mood, alt = false))
                }
                setImageViewResource(R.id.widget_pip_alt, pipDrawable(mood, alt = true))
                setTextViewText(R.id.widget_streak, if (streak == 1) "1 day" else "$streak days")
                setTextViewText(R.id.widget_grade, "Grade " + (grade?.let { "$it%" } ?: "–"))
                setOnClickPendingIntent(R.id.widget_root, open)
            }
        }
    }
}
