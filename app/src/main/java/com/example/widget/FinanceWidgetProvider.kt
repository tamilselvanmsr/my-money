package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.FinanceDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class FinanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val decFormat = DecimalFormat("₹#,##0.00")
        val currentMonthYear = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Retrieve live details in a non-blocking context
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = FinanceDatabase.getDatabase(context)
                    val txs = db.financeDao().getAllTransactions().first()

                    var totalIncome = 0.0
                    var totalExpense = 0.0
                    var monthlyExpense = 0.0

                    val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())

                    for (tx in txs) {
                        if (tx.type == "INCOME") {
                            totalIncome += tx.amount
                        } else {
                            totalExpense += tx.amount
                            val txMonthYear = sdf.format(Date(tx.timestamp))
                            if (txMonthYear == currentMonthYear) {
                                monthlyExpense += tx.amount
                            }
                        }
                    }

                    val netBalance = totalIncome - totalExpense

                    views.setTextViewText(R.id.widget_net_balance, decFormat.format(netBalance))
                    views.setTextViewText(R.id.widget_monthly_expense, "Spent this month: ₹${decFormat.format(monthlyExpense)}")
                } catch (e: Exception) {
                    views.setTextViewText(R.id.widget_net_balance, "Rupee Ledger")
                    views.setTextViewText(R.id.widget_monthly_expense, "Tap to synchronize")
                }

                // Attach click intents securely
                val intent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_background, pendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
