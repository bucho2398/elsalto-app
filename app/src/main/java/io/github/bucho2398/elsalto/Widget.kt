package io.github.bucho2398.elsalto

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Home-screen widget: the weather and your favourite walls (or the driest ones), refreshed every 30 minutes. */
class ConditionsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        schedule(ctx)
        WorkManager.getInstance(ctx).enqueueUniqueWork("widget-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build())
    }

    override fun onEnabled(ctx: Context) = schedule(ctx)

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("widget")
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("widget", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Redraws every placed widget from these conditions (also called by the app after it refreshes). */
        fun render(ctx: Context, c: Conditions?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ConditionsWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            if (c == null) {
                views.setTextViewText(R.id.w_weather, "weather unavailable · tap to retry")
            } else {
                val cur = c.current
                views.setTextViewText(R.id.w_updated, Fmt.time(c.updated, c.offset, "HH:mm"))
                views.setTextViewText(
                    R.id.w_weather,
                    "${weatherGlyph(cur.optInt("weather_code"), cur.optInt("is_day") == 1)} ${cur.optDouble("temperature_2m").roundToInt()}° · " +
                        "${cur.optInt("relative_humidity_2m")}% · ${cur.optDouble("wind_speed_10m").roundToInt()} km/h",
                )
                views.setTextViewText(
                    R.id.w_rain,
                    "rain ${Fmt.mm(c.rainPast[0])}/${Fmt.mm(c.rainPast[2])} mm past 24/72 h · ${Fmt.mm(c.rainNext[0])} mm next 24 h",
                )
                val favs = c.walls.filter { it.fav }
                val shown = (favs.ifEmpty { c.walls.filter { it.wall.count > 0 }.sortedBy { it.wetPct } }).take(4)
                val sb = SpannableStringBuilder()
                for (w in shown) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    val color = when (w.state) { "dry" -> 0xFF7FD8A6.toInt(); "damp" -> 0xFFE8C468.toInt(); else -> 0xFFE8806A.toInt() }
                    val start = sb.length
                    sb.append("● ")
                    sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(w.wall.name)
                    val s2 = sb.length
                    sb.append("  ${w.state} ${w.wetPct}% · ${w.aspect ?: "?"} · ${w.sunHours} h sun")
                    sb.setSpan(ForegroundColorSpan(0xFF8A90A0.toInt()), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (favs.isEmpty() && shown.isNotEmpty()) sb.append("\n(driest walls · ★ walls in the app to pin them)")
                views.setTextViewText(R.id.w_walls, sb)
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = runCatching { Repo.conditions(applicationContext) }.getOrNull()
        ConditionsWidget.render(applicationContext, c)
        return Result.success()
    }
}
