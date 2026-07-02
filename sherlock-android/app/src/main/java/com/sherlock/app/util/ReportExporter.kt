package com.sherlock.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sherlock.app.data.db.CaseEntity
import com.sherlock.app.data.db.FindingEntity
import com.sherlock.app.data.db.SubjectEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun buildHtml(
        case: CaseEntity,
        subjects: List<SubjectEntity>,
        findings: List<FindingEntity>
    ): String {
        val bySubject = findings.groupBy { it.subjectId }
        val sb = StringBuilder()
        sb.append(
            """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(case.name)} — OSINT Report</title>
<style>
body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0d0d0d;color:#e0e0e0;margin:0;padding:24px;}
h1{color:#00e676;margin:0 0 4px;} .muted{color:#888;font-size:13px;}
.subject{background:#1a1a1a;border:1px solid #2a2a2a;border-radius:10px;padding:16px;margin:16px 0;}
.stype{display:inline-block;background:#00e676;color:#000;font-weight:bold;border-radius:4px;padding:2px 8px;font-size:12px;}
.derived{background:#00bcd4;}
.finding{border-left:3px solid #2a2a2a;padding:6px 12px;margin:8px 0;}
.hit{border-left-color:#00e676;} .miss{border-left-color:#552; color:#888;}
.finding b{color:#fff;} a{color:#00e676;word-break:break-all;} .src{color:#00bcd4;font-size:12px;}
</style></head><body>
<h1>${esc(case.name)}</h1>
<div class="muted">${esc(case.description)}</div>
<div class="muted">Created ${df.format(Date(case.createdAt))} · ${subjects.size} subjects · ${findings.size} findings · Report ${df.format(Date())}</div>
"""
        )
        subjects.forEach { s ->
            val fs = bySubject[s.id].orEmpty()
            val derivedClass = if (s.origin != "manual") " derived" else ""
            sb.append("""<div class="subject"><span class="stype$derivedClass">${esc(s.subjectType.label)}</span> <b>${esc(s.value)}</b>""")
            if (s.origin != "manual") sb.append(""" <span class="muted">(${esc(s.origin)})</span>""")
            if (fs.isEmpty()) sb.append("""<div class="muted">No findings.</div>""")
            fs.forEach { f ->
                val cls = if (f.positive) "hit" else "miss"
                sb.append("""<div class="finding $cls"><span class="src">${esc(f.source)}</span> <b>${esc(f.title)}</b>""")
                if (f.detail.isNotBlank()) sb.append(" — ${esc(f.detail)}")
                if (f.url.isNotBlank()) sb.append(""" <br><a href="${esc(f.url)}">${esc(f.url)}</a>""")
                sb.append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Writes the report to cache and fires a share intent. Returns false on failure. */
    fun share(context: Context, case: CaseEntity, html: String): Boolean {
        return try {
            val safe = case.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(40).ifBlank { "case" }
            val file = File(context.cacheDir, "report_${safe}.html")
            file.writeText(html)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OSINT Report: ${case.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
