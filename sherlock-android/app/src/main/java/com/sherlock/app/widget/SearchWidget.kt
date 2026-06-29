package com.sherlock.app.widget

import android.content.Context
import android.content.Intent
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sherlock.app.MainActivity

class SearchWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .background(ColorProvider(android.graphics.Color.parseColor("#161B22")))
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔍",
                    style = TextStyle(fontSize = 32.sp)
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = "Sherlock",
                    style = TextStyle(
                        color = ColorProvider(android.graphics.Color.parseColor("#58A6FF")),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = "לחץ לחיפוש",
                    style = TextStyle(
                        color = ColorProvider(android.graphics.Color.parseColor("#8B949E")),
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}

class SearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SearchWidget()
}
