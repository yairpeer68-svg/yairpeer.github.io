package com.sherlock.app.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sherlock.app.data.model.DigitalIdentity
import com.sherlock.app.data.model.Favorite
import com.sherlock.app.data.model.IdentityLink
import com.sherlock.app.data.model.ProfileNote
import com.sherlock.app.data.model.Project
import com.sherlock.app.data.model.ProjectTask
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.data.model.SearchResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareFile(uri: Uri, mimeType: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "שתף").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun exportToCsv(results: List<SearchResult>, username: String): Uri {
        val sb = StringBuilder()
        sb.appendLine("Site,URL,Status,Category,Response Time (ms)")
        for (r in results) {
            sb.appendLine("\"${r.siteName}\",\"${r.url}\",${if (r.exists) "Found" else "Not Found"},\"${r.category.displayName}\",${r.responseTimeMs}")
        }

        val file = File(context.cacheDir, "sherlock_${username}_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportToHtml(results: List<SearchResult>, username: String): Uri {
        val found = results.filter { it.exists }
        val sb = StringBuilder()
        sb.appendLine("""
            <!DOCTYPE html>
            <html dir="rtl" lang="he">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Sherlock Report - $username</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0d1117; color: #c9d1d9; padding: 20px; }
                    h1 { color: #58a6ff; } h2 { color: #8b949e; }
                    .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 16px; margin: 8px 0; }
                    .found { border-right: 4px solid #3fb950; }
                    a { color: #58a6ff; text-decoration: none; }
                    .stats { display: flex; gap: 16px; margin: 16px 0; }
                    .stat { background: #161b22; border-radius: 12px; padding: 16px; flex: 1; text-align: center; }
                    .stat-num { font-size: 2em; font-weight: bold; color: #58a6ff; }
                </style>
            </head>
            <body>
                <h1>🔍 Sherlock Report</h1>
                <h2>שם משתמש: $username</h2>
                <div class="stats">
                    <div class="stat"><div class="stat-num">${found.size}</div>נמצאו</div>
                    <div class="stat"><div class="stat-num">${results.size}</div>נבדקו</div>
                    <div class="stat"><div class="stat-num">${if (results.isNotEmpty()) (found.size * 100 / results.size) else 0}%</div>אחוז הצלחה</div>
                </div>
        """.trimIndent())

        val grouped = found.groupBy { it.category }
        for ((category, items) in grouped) {
            sb.appendLine("<h2>${category.hebrewName} (${items.size})</h2>")
            for (item in items) {
                sb.appendLine("""<div class="card found"><a href="${item.url}" target="_blank">${item.siteName}</a> - ${item.responseTimeMs}ms</div>""")
            }
        }

        sb.appendLine("</body></html>")

        val file = File(context.cacheDir, "sherlock_${username}_${System.currentTimeMillis()}.html")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun shareResults(results: List<SearchResult>, username: String) {
        val found = results.filter { it.exists }
        val text = buildString {
            appendLine("🔍 Sherlock Report - @$username")
            appendLine("נמצאו ${found.size} מתוך ${results.size} אתרים")
            appendLine()
            for (r in found) {
                appendLine("✅ ${r.siteName}: ${r.url}")
            }
            appendLine()
            appendLine("Generated by Sherlock for Android")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Sherlock Report - $username")
        }
        context.startActivity(Intent.createChooser(intent, "שתף תוצאות").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun exportHistoryToCsv(history: List<SearchHistory>): Uri {
        val sb = StringBuilder()
        sb.appendLine("Query,Type,Found,Checked,Date")
        for (h in history) {
            sb.appendLine("\"${h.query}\",${h.searchType.name},${h.totalFound},${h.totalChecked},${dateFormat.format(Date(h.timestamp))}")
        }
        val file = File(context.cacheDir, "sherlock_history_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportHistoryToHtml(history: List<SearchHistory>): Uri {
        val sb = StringBuilder()
        sb.appendLine(htmlHeader("היסטוריית חיפושים"))
        for (h in history) {
            sb.appendLine("""<div class="card"><b>${h.query}</b> (${h.searchType.hebrewName}) - ${h.totalFound}/${h.totalChecked} - ${dateFormat.format(Date(h.timestamp))}</div>""")
        }
        sb.appendLine("</body></html>")
        val file = File(context.cacheDir, "sherlock_history_${System.currentTimeMillis()}.html")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportFavoritesToCsv(favorites: List<Favorite>): Uri {
        val sb = StringBuilder()
        sb.appendLine("Site,URL,Username,Category,Tag,Notes")
        for (f in favorites) {
            sb.appendLine("\"${f.siteName}\",\"${f.url}\",\"${f.username}\",\"${f.category.displayName}\",\"${f.tag}\",\"${f.notes}\"")
        }
        val file = File(context.cacheDir, "sherlock_favorites_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportFavoritesToHtml(favorites: List<Favorite>): Uri {
        val sb = StringBuilder()
        sb.appendLine(htmlHeader("מועדפים"))
        for (f in favorites) {
            sb.appendLine("""<div class="card"><a href="${f.url}" target="_blank">${f.siteName}</a> - @${f.username} ${if (f.tag.isNotEmpty()) "• ${f.tag}" else ""}</div>""")
        }
        sb.appendLine("</body></html>")
        val file = File(context.cacheDir, "sherlock_favorites_${System.currentTimeMillis()}.html")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportNotesToText(notes: List<ProfileNote>): Uri {
        val sb = StringBuilder()
        sb.appendLine("Sherlock - הערות חקירה")
        sb.appendLine("=".repeat(40))
        for (n in notes) {
            sb.appendLine()
            sb.appendLine("${n.siteName} - @${n.username}")
            sb.appendLine(n.profileUrl)
            sb.appendLine(dateFormat.format(Date(n.timestamp)))
            sb.appendLine(com.sherlock.app.util.EncryptionManager.decrypt(n.note))
            sb.appendLine("-".repeat(30))
        }
        val file = File(context.cacheDir, "sherlock_notes_${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportProjectReport(project: Project, tasks: List<ProjectTask>, notes: List<ProfileNote>): Uri {
        val sb = StringBuilder()
        sb.appendLine(htmlHeader("דוח חקירה - ${project.name}"))
        sb.appendLine("""<div class="card"><b>סטטוס:</b> ${project.status.hebrewName} • <b>עדיפות:</b> ${project.priority.hebrewName}</div>""")
        if (project.description.isNotBlank()) {
            sb.appendLine("""<div class="card">${project.description}</div>""")
        }
        sb.appendLine("<h2>משימות (${tasks.size})</h2>")
        for (t in tasks) {
            sb.appendLine("""<div class="card">${if (t.isDone) "✅" else "⬜"} ${t.text}</div>""")
        }
        sb.appendLine("<h2>הערות (${notes.size})</h2>")
        for (n in notes) {
            sb.appendLine("""<div class="card"><b>${n.siteName}</b> - @${n.username}<br>${com.sherlock.app.util.EncryptionManager.decrypt(n.note)}</div>""")
        }
        sb.appendLine("</body></html>")
        val file = File(context.cacheDir, "sherlock_project_${project.id}_${System.currentTimeMillis()}.html")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportIdentityReport(identity: DigitalIdentity, links: List<IdentityLink>): Uri {
        val sb = StringBuilder()
        sb.appendLine(htmlHeader("כרטיס זהות - ${identity.name}"))
        if (identity.notes.isNotBlank()) {
            sb.appendLine("""<div class="card">${identity.notes}</div>""")
        }
        sb.appendLine("<h2>פרופילים מקושרים (${links.size})</h2>")
        for (l in links) {
            sb.appendLine("""<div class="card"><b>${l.platform}</b><br><a href="${l.url}" target="_blank">${l.url}</a></div>""")
        }
        sb.appendLine("</body></html>")
        val file = File(context.cacheDir, "sherlock_identity_${identity.id}_${System.currentTimeMillis()}.html")
        file.writeText(sb.toString())
        return fileUri(file)
    }

    fun exportFullBackupZip(
        history: List<SearchHistory>,
        favorites: List<Favorite>,
        notes: List<ProfileNote>,
        projects: List<Project>,
        tasks: List<ProjectTask>,
        identities: List<DigitalIdentity>,
        links: List<IdentityLink>
    ): Uri {
        val file = File(context.cacheDir, "sherlock_backup_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            writeZipEntry(zip, "history.json", gson.toJson(history))
            writeZipEntry(zip, "favorites.json", gson.toJson(favorites))
            writeZipEntry(zip, "notes.json", gson.toJson(notes))
            writeZipEntry(zip, "projects.json", gson.toJson(projects))
            writeZipEntry(zip, "project_tasks.json", gson.toJson(tasks))
            writeZipEntry(zip, "digital_identities.json", gson.toJson(identities))
            writeZipEntry(zip, "identity_links.json", gson.toJson(links))
        }
        return fileUri(file)
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    fun generateSummaryCardImage(title: String, bulletPoints: List<String>): Uri {
        val width = 1080
        val lineHeight = 64
        val height = 360 + bulletPoints.size * lineHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#0D1117"))

        val accentPaint = Paint().apply { color = Color.parseColor("#58A6FF") }
        canvas.drawRect(0f, 0f, width.toFloat(), 12f, accentPaint)

        val brandPaint = Paint().apply {
            color = Color.parseColor("#8B949E")
            textSize = 36f
            isAntiAlias = true
        }
        canvas.drawText("🔍 Sherlock OSINT Report", 60f, 100f, brandPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText(title, 60f, 200f, titlePaint)

        val bulletPaint = Paint().apply {
            color = Color.parseColor("#C9D1D9")
            textSize = 38f
            isAntiAlias = true
        }
        var y = 300f
        for (point in bulletPoints) {
            canvas.drawText("• $point", 60f, y, bulletPaint)
            y += lineHeight
        }

        val file = File(context.cacheDir, "sherlock_summary_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return fileUri(file)
    }

    private fun htmlHeader(title: String): String = """
        <!DOCTYPE html>
        <html dir="rtl" lang="he">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0d1117; color: #c9d1d9; padding: 20px; }
                h1 { color: #58a6ff; } h2 { color: #8b949e; }
                .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 16px; margin: 8px 0; }
                a { color: #58a6ff; text-decoration: none; }
            </style>
        </head>
        <body>
            <h1>🔍 $title</h1>
    """.trimIndent()
}
