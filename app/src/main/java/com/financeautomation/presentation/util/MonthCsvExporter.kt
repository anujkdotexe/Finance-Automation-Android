package com.financeautomation.presentation.util

import android.content.Context
import android.content.Intent
import com.financeautomation.FinanceApplication
import com.financeautomation.data.export.CsvExportWriter
import com.financeautomation.domain.usecase.ExportMonthCsvUseCase
import java.util.Calendar
import java.util.TimeZone

/**
 * Utility executing CSV export and launching the Android system share sheet.
 */
object MonthCsvExporter {
    suspend fun exportMonth(context: Context, monthOffset: Int): Result<Unit> {
        return runCatching {
            val app = context.applicationContext as FinanceApplication
            val exportUseCase = ExportMonthCsvUseCase(
                app.appContainer.transactionRepository,
                app.appContainer.inflowRepository
            )
            val writer = CsvExportWriter(context)

            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
                add(Calendar.MONTH, monthOffset)
            }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1

            val csv = exportUseCase(year, month)
            val uri = writer.write(year, month, csv)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Export transactions").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
