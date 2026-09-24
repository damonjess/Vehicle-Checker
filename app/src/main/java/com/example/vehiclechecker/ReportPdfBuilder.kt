package com.example.vehiclechecker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a simple one-page PDF report for a checked vehicle. */
object ReportPdfBuilder {

    private const val PAGE_WIDTH = 595  // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842

    fun build(context: Context, vehicle: VehicleData, mot: MotHistoryData?): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(14, 74, 103)
            textSize = 22f
            isFakeBoldText = true
        }
        val headingPaint = Paint().apply {
            color = Color.rgb(14, 74, 103)
            textSize = 14f
            isFakeBoldText = true
        }
        val labelPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
        }
        val valuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
        }

        var y = 50f
        val margin = 40f
        val colValue = 200f

        canvas.drawText("Vehicle Report", margin, y, titlePaint)
        y += 10f
        canvas.drawText(
            "Generated ${SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date())}",
            margin, y, labelPaint
        )
        y += 24f

        fun row(label: String, value: String) {
            canvas.drawText(label, margin, y, labelPaint)
            canvas.drawText(value.ifBlank { "—" }, margin + colValue, y, valuePaint)
            y += 17f
        }

        fun heading(text: String) {
            y += 10f
            canvas.drawText(text, margin, y, headingPaint)
            y += 17f
        }

        heading("Vehicle")
        row("Registration", vehicle.registration)
        row("Make", vehicle.make)
        row("Colour", vehicle.colour)
        row("Fuel type", vehicle.fuelType)
        row("Year of manufacture", vehicle.yearOfManufacture)
        row("First registered", vehicle.firstRegistered)
        row("Engine size", vehicle.engineSize)
        row("CO2 emissions", vehicle.co2Emissions)
        row("Wheelplan", vehicle.wheelplan)
        row("Last V5C issued", vehicle.lastV5cIssued)

        heading("Tax & MOT")
        row("Tax status", vehicle.taxStatus)
        row("Tax due", vehicle.taxDueDate)
        row("MOT status", vehicle.motStatus)
        row("MOT expiry", vehicle.motExpiryDate)

        if (mot != null) {
            heading("MOT History (${mot.tests.size} tests)")
            if (mot.tests.isEmpty()) {
                canvas.drawText("No MOT tests recorded.", margin, y, bodyPaint)
                y += 16f
            }
            mot.tests.take(10).forEach { test ->
                val line = "${test.dateTested} — ${test.result} — ${test.mileage.ifBlank { "mileage not recorded" }}"
                canvas.drawText(line, margin, y, bodyPaint)
                y += 14f
                test.advisories.forEach { advisory ->
                    canvas.drawText("   • $advisory", margin, y, labelPaint)
                    y += 13f
                }
                if (y > PAGE_HEIGHT - 60f) return@forEach
            }
        }

        doc.finishPage(page)

        val outDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val outFile = File(outDir, "vehicle-report-${vehicle.registration.replace(" ", "")}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }
}
