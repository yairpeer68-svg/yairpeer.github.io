package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class InfoRow(val label: String, val value: String)

@Composable
fun IntelligenceDetailsColumn(
    intelligence: IntelligenceSummary,
    reportStatus: String? = null,
    reportId: String? = null,
    onSignReport: (() -> Unit)? = null,
    onDeleteReport: (() -> Unit)? = null,
    onPivotTarget: ((String) -> Unit)? = null
) {
    val deep = intelligence.deepIntelligence
    val fileIntel = intelligence.fileIntelligence
    val usefulRows = remember(intelligence.jobId) { collectUsefulRows(intelligence) }
    val assets = remember(intelligence.jobId) { collectUsefulAssets(intelligence) }
    val priorityInsights = remember(intelligence.jobId) { jsonObjects(deep.optJSONArray("priority_insights")) }
    var query by remember(intelligence.jobId) { mutableStateOf("") }
    var showAllFindings by remember(intelligence.jobId) { mutableStateOf(false) }
    var showAllInfo by remember(intelligence.jobId) { mutableStateOf(false) }
    var showAllAssets by remember(intelligence.jobId) { mutableStateOf(false) }

    val normalizedQuery = query.trim().lowercase()
    val orderedFindings = remember(intelligence.jobId, normalizedQuery) {
        intelligence.findings
            .filter { normalizedQuery.isBlank() || searchableFinding(it).contains(normalizedQuery) }
            .sortedWith(compareByDescending<JSONObject> { findingRisk(it) }.thenBy { it.optString("title") })
    }
    val filteredRows = remember(intelligence.jobId, normalizedQuery) {
        usefulRows.filter { normalizedQuery.isBlank() || "${it.label} ${it.value}".lowercase().contains(normalizedQuery) }
    }
    val filteredAssets = remember(intelligence.jobId, normalizedQuery) {
        assets.filter { normalizedQuery.isBlank() || it.lowercase().contains(normalizedQuery) }
    }
    val filteredInsights = remember(intelligence.jobId, normalizedQuery) {
        priorityInsights.filter { normalizedQuery.isBlank() || searchableFinding(it).contains(normalizedQuery) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ResultOverviewCard(intelligence)

        if (intelligence.fileType == "target" || deep.length() > 0 || fileIntel.length() > 0) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("חיפוש בתוך התוצאה") },
                placeholder = { Text(if (intelligence.fileType == "target") "IP, פורט, טכנולוגיה, ממצא…" else "דומיין, הרשאה, רכיב, יכולת, ממצא…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
            )
        }

        SmartSummaryCard(intelligence)

        if (intelligence.fileType != "target" && fileIntel.length() > 0) {
            FileBehaviorCard(fileIntel)
            FileIndicatorsCard(fileIntel, onPivotTarget)
            FileComponentsSecurityCard(fileIntel)
            FileSimilarityCard(fileIntel)
        }

        val changes = if (intelligence.fileType == "target") {
            deep.optJSONObject("changes") ?: intelligence.changes
        } else {
            fileIntel.optJSONObject("changes") ?: intelligence.artifactChanges
        }
        if (changes.optBoolean("has_previous", false)) {
            ChangeSummaryCard(changes)
        }

        val websiteExposure = deep.optJSONObject("website_exposure")
            ?: intelligence.modules.optJSONObject("exposure")
        if (websiteExposure != null && websiteExposure.length() > 0) {
            WebsiteExposureCard(websiteExposure)
        }

        val attackSurface = deep.optJSONObject("attack_surface")
        if (attackSurface != null && attackSurface.length() > 0) {
            AttackSurfaceCard(attackSurface)
        }

        val history = jsonObjects(deep.optJSONArray("history"))
        if (history.isNotEmpty()) {
            TargetTimelineCard(history)
        }

        val relationshipRows = collectRelationshipRows(deep)
        if (relationshipRows.isNotEmpty()) {
            RelationshipMapCard(relationshipRows)
        }

        if (filteredInsights.isNotEmpty()) {
            SectionCard {
                Text("מה חשוב עכשיו", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "הממצאים בעלי העדיפות הגבוהה ביותר לאחר חיבור כל מקורות המידע.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                filteredInsights.take(6).forEachIndexed { index, insight ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    FindingSummaryCard(insight, preferRiskScoreField = true)
                }
            }
        }

        if (filteredRows.isNotEmpty()) {
            SectionCard {
                Text("המידע החשוב", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val visible = if (showAllInfo || normalizedQuery.isNotBlank()) filteredRows else filteredRows.take(14)
                visible.forEachIndexed { index, row ->
                    if (index > 0) {
                        Spacer(Modifier.height(9.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Spacer(Modifier.height(9.dp))
                    }
                    Column {
                        Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (showAllInfo || normalizedQuery.isNotBlank()) 10 else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (normalizedQuery.isBlank() && filteredRows.size > 14) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showAllInfo = !showAllInfo }) {
                        Text(if (showAllInfo) "הצג פחות" else "הצג עוד ${filteredRows.size - 14}")
                    }
                }
            }
        }

        if (orderedFindings.isNotEmpty()) {
            SectionCard {
                Text("ממצאים (${orderedFindings.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val visible = if (showAllFindings || normalizedQuery.isNotBlank()) orderedFindings else orderedFindings.take(8)
                visible.forEachIndexed { index, finding ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    FindingSummaryCard(finding)
                }
                if (normalizedQuery.isBlank() && orderedFindings.size > 8) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showAllFindings = !showAllFindings }) {
                        Text(if (showAllFindings) "הצג פחות" else "הצג את כל הממצאים")
                    }
                }
            }
        }

        if (filteredAssets.isNotEmpty()) {
            SectionCard {
                Text("נכסים וקשרים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "דומיינים, כתובות IP, תעודות, שרתי DNS וישויות שימושיות שנמצאו.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                val visible = if (showAllAssets || normalizedQuery.isNotBlank()) filteredAssets else filteredAssets.take(16)
                visible.forEachIndexed { index, value ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Text(
                            value,
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (normalizedQuery.isBlank() && filteredAssets.size > 16) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showAllAssets = !showAllAssets }) {
                        Text(if (showAllAssets) "הצג פחות" else "הצג עוד ${filteredAssets.size - 16}")
                    }
                }
            }
        }

        if (normalizedQuery.isNotBlank() && orderedFindings.isEmpty() && filteredRows.isEmpty() && filteredAssets.isEmpty() && filteredInsights.isEmpty()) {
            SectionCard {
                Text("לא נמצאו תוצאות לחיפוש", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("נסה מונח אחר.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (onSignReport != null || reportId != null) {
            SectionCard {
                Text("דוח", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "אפשר ליצור דוח חתום ולאמת אותו מול השרת.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onSignReport != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onSignReport, modifier = Modifier.fillMaxWidth()) { Text("צור דוח מאומת") }
                }
                reportStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (reportId != null && onDeleteReport != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDeleteReport, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("מחק דוח")
                    }
                }
            }
        }

        if (orderedFindings.isEmpty() && filteredRows.isEmpty() && filteredAssets.isEmpty() && normalizedQuery.isBlank()) {
            SectionCard {
                Text("הניתוח הושלם", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "לא התקבל מידע משמעותי להצגה מהבדיקות הנוכחיות.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SmartSummaryCard(intelligence: IntelligenceSummary) {
    val deep = intelligence.deepIntelligence
    val fileIntel = intelligence.fileIntelligence
    val summary = sequenceOf(
        fileIntel.optString("ai_summary"),
        deep.optString("ai_summary"),
        intelligence.aiSummary.orEmpty(),
        fileIntel.optString("summary_he"),
        fileIntel.optString("summary"),
        deep.optString("summary_he"),
        deep.optString("summary"),
        intelligence.executive.optString("summary"),
        intelligence.executive.optString("overview")
    ).firstOrNull { it.isNotBlank() }

    if (!summary.isNullOrBlank()) {
        SectionCard {
            Text("סיכום חכם", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChangeSummaryCard(changes: JSONObject) {
    val risk = changes.optJSONObject("risk") ?: JSONObject()
    val delta = risk.optInt("delta", 0)
    val rows = buildList {
        addAll(changeRows("כתובות IP", changes.optJSONObject("ips")))
        addAll(changeRows("שירותים", changes.optJSONObject("open_ports")))
        addAll(changeRows("טכנולוגיות", changes.optJSONObject("technologies")))
        addAll(changeRows("הרשאות", changes.optJSONObject("permissions")))
        addAll(changeRows("יכולות", changes.optJSONObject("capabilities")))
        addAll(changeRows("דומיינים", changes.optJSONObject("domains")))
        addAll(changeRows("כתובות URL", changes.optJSONObject("urls")))
        addAll(changeRows("ספריות Native", changes.optJSONObject("native_libraries")))
        addAll(changeRows("Imports", changes.optJSONObject("imports")))
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("מה השתנה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (changes.optBoolean("changed", false)) "נמצאו שינויים לעומת הבדיקה הקודמת" else "לא נמצאו שינויים מהותיים",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (delta != 0) {
                Surface(shape = RoundedCornerShape(999.dp), color = riskAccentShared(kotlin.math.abs(delta) + 45).copy(alpha = 0.15f)) {
                    Text(
                        "סיכון ${if (delta > 0) "+" else ""}$delta",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            rows.take(12).forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text("• ${row.label}: ${row.value}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun changeRows(label: String, obj: JSONObject?): List<InfoRow> {
    if (obj == null) return emptyList()
    val rows = mutableListOf<InfoRow>()
    val added = jsonStrings(obj.optJSONArray("added"))
    val removed = jsonStrings(obj.optJSONArray("removed"))
    if (added.isNotEmpty()) rows += InfoRow("נוסף $label", added.take(6).joinToString(", "))
    if (removed.isNotEmpty()) rows += InfoRow("הוסר $label", removed.take(6).joinToString(", "))
    return rows
}

@Composable
private fun FileBehaviorCard(fileIntel: JSONObject) {
    val behavior = fileIntel.optJSONObject("behavior") ?: return
    val capabilities = jsonObjects(behavior.optJSONArray("capabilities"))
    val summary = behavior.optString("summary_he").ifBlank { behavior.optString("summary") }
    if (capabilities.isEmpty() && summary.isBlank()) return
    SectionCard {
        Text("יכולות והתנהגות", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (capabilities.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            capabilities.take(12).forEachIndexed { index, cap ->
                if (index > 0) Spacer(Modifier.height(7.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(cap.optString("name", "יכולת"), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        val evidence = jsonStrings(cap.optJSONArray("evidence"))
                        if (evidence.isNotEmpty()) {
                            Text(
                                evidence.take(3).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileIndicatorsCard(fileIntel: JSONObject, onPivotTarget: ((String) -> Unit)? = null) {
    val iocs = fileIntel.optJSONObject("iocs") ?: return
    val domains = jsonStrings(iocs.optJSONArray("domains"))
    val urls = jsonStrings(iocs.optJSONArray("urls"))
    val ips = jsonStrings(iocs.optJSONArray("ip_addresses"))
    val paths = jsonStrings(iocs.optJSONArray("api_paths"))
    val rows = buildList {
        if (domains.isNotEmpty()) add(InfoRow("דומיינים (${domains.size})", domains.take(8).joinToString("\n")))
        if (urls.isNotEmpty()) add(InfoRow("כתובות URL (${urls.size})", urls.take(8).joinToString("\n")))
        if (ips.isNotEmpty()) add(InfoRow("כתובות IP (${ips.size})", ips.take(8).joinToString(", ")))
        if (paths.isNotEmpty()) add(InfoRow("נתיבי API (${paths.size})", paths.take(8).joinToString("\n")))
    }
    if (rows.isEmpty()) return
    SectionCard {
        Text("תקשורת ו־IOC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "כתובות ותשתיות שנמצאו סטטית בתוך הקובץ. סריקת רשת אינה מופעלת אוטומטית.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
            }
            Text(row.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(3.dp))
            Text(row.value, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
        val pivot = domains.firstOrNull() ?: ips.firstOrNull()
        if (pivot != null && onPivotTarget != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onPivotTarget(pivot) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("פתח בסריקת דומיין / IP")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "היעד יועתק למסך הדומיין. סריקת הרשת תתחיל רק אחרי אישור הרשאה ולחיצה על סרוק הכל.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileComponentsSecurityCard(fileIntel: JSONObject) {
    val components = jsonObjects(fileIntel.optJSONArray("components"))
    val secrets = fileIntel.optJSONObject("secrets") ?: JSONObject()
    val packing = fileIntel.optJSONObject("packing_obfuscation") ?: JSONObject()
    val cve = fileIntel.optJSONObject("cve_correlation") ?: JSONObject()
    val cveMatches = jsonObjects(cve.optJSONArray("matches"))
    val packingItems = jsonObjects(packing.optJSONArray("indicators"))
    val secretTotal = secrets.optInt("total", 0)
    if (components.isEmpty() && secretTotal == 0 && packingItems.isEmpty() && cveMatches.isEmpty()) return

    SectionCard {
        Text("רכיבים ואבטחת הקובץ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("רכיבים", components.size.toString(), Modifier.weight(1f))
            MetricCard("סודות", secretTotal.toString(), Modifier.weight(1f), if (secretTotal > 0) riskAccentShared(70) else MaterialTheme.colorScheme.primary)
            MetricCard("CVE", cveMatches.size.toString(), Modifier.weight(1f), if (cveMatches.isNotEmpty()) riskAccentShared(75) else MaterialTheme.colorScheme.primary)
        }
        if (secretTotal > 0) {
            Spacer(Modifier.height(10.dp))
            Text("נמצאו אינדיקציות אפשריות לסודות. הערכים עצמם מוסתרים ואינם מוצגים באפליקציה.", style = MaterialTheme.typography.bodySmall)
        }
        if (packingItems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Packing / Obfuscation", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            packingItems.take(6).forEach { item ->
                Text("• ${item.optString("name", "indicator")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (components.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("רכיבים שזוהו", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            components.take(12).forEach { component ->
                val version = component.optString("version")
                Text(
                    "• ${component.optString("name", "component")}${if (version.isNotBlank()) "  $version" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (cveMatches.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("התאמות CVE מדויקות", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            cveMatches.take(8).forEach { row ->
                Text(
                    "• ${row.optString("cve")} • ${row.optString("component")} ${row.optString("version")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (cve.optString("status").isNotBlank() && cve.optString("status") != "ok") {
            Spacer(Modifier.height(8.dp))
            Text(
                "התאמת CVE דורשת קטלוג מקומי מעודכן בשרת; המערכת לא מנחשת CVE לפי שם בלבד.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileSimilarityCard(fileIntel: JSONObject) {
    val similar = jsonObjects(fileIntel.optJSONArray("similar"))
    val history = jsonObjects(fileIntel.optJSONArray("history"))
    if (similar.isEmpty() && history.isEmpty()) return
    SectionCard {
        Text("גרסאות ודמיון", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("ניתוחים קודמים של אותו שם קובץ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            history.take(5).forEach { row ->
                Text(
                    "• ${timelineDate(row.optString("created_at"))} • סיכון ${row.optInt("risk_score", 0)} • ${row.optInt("finding_count", 0)} ממצאים",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("קבצים דומים לפי מאפיינים סטטיים", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            similar.take(6).forEach { row ->
                Text(
                    "• ${row.optString("filename", "קובץ")} • ${row.optInt("similarity", 0)}% דמיון",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WebsiteExposureCard(exposure: JSONObject) {
    val score = exposure.optInt("score", 0)
    val band = exposure.optString("band", "low")
    val counts = exposure.optJSONObject("cve_counts") ?: JSONObject()
    val cves = jsonObjects(exposure.optJSONArray("cve_matches"))
    val services = jsonObjects(exposure.optJSONArray("services"))
    val dns = exposure.optJSONObject("dns_security") ?: JSONObject()
    val http = exposure.optJSONObject("http_security") ?: JSONObject()
    val tls = exposure.optJSONObject("tls") ?: JSONObject()

    SectionCard {
        Text("Website Security & Exposure", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "תמונת חשיפה מאוחדת: שירותים ציבוריים, טכנולוגיות, CVE, DNS, HTTP ו־TLS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("חשיפה", "$score/100", Modifier.weight(1f), riskAccentShared(score))
            MetricCard("CVE", counts.optInt("total", cves.size).toString(), Modifier.weight(1f))
            MetricCard("KEV", counts.optInt("kev", 0).toString(), Modifier.weight(1f), if (counts.optInt("kev", 0) > 0) riskAccentShared(92) else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "רמה: ${band.uppercase()} • מאומת ${counts.optInt("confirmed", 0)} • סביר ${counts.optInt("probable", 0)} • אפשרי ${counts.optInt("possible", 0)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (cves.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("CVE רלוונטיים", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            cves.take(12).forEachIndexed { index, cve ->
                if (index > 0) Spacer(Modifier.height(7.dp))
                val id = cve.optString("cve_id", "CVE")
                val confidence = cve.optString("confidence", "possible")
                val cvss = if (cve.has("cvss_score") && !cve.isNull("cvss_score")) cve.optDouble("cvss_score", 0.0) else null
                val epss = if (cve.has("epss_score") && !cve.isNull("epss_score")) cve.optDouble("epss_score", 0.0) else null
                val tech = cve.optString("technology")
                val version = cve.optString("detected_version")
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(id, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (confidence) { "confirmed" -> "מאומת"; "probable" -> "סביר"; else -> "אפשרי" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            buildString {
                                if (tech.isNotBlank()) append(tech)
                                if (version.isNotBlank()) append(" $version")
                                if (cvss != null) append(" • CVSS ${"%.1f".format(cvss)}")
                                if (epss != null) append(" • EPSS ${"%.3f".format(epss)}")
                                if (cve.optBoolean("kev", false)) append(" • CISA KEV")
                                if (cve.optBoolean("known_ransomware", false)) append(" • Ransomware")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        cve.optString("reason").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(3.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        val sensitive = services.filter { it.optBoolean("sensitive", false) }
        if (sensitive.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("שירותים רגישים שחשופים", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            sensitive.take(10).forEach { service ->
                Text(
                    "• ${service.optInt("port", 0)}/${service.optString("service", "service")} — ${service.optString("recommendation")}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Security posture", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(
            buildString {
                append(if (dns.optBoolean("spf_present", false)) "SPF ✓" else "SPF חסר")
                append(" • ")
                append(if (dns.optBoolean("dmarc_present", false)) "DMARC ✓" else "DMARC חסר")
                append(" • ")
                append(if (dns.optBoolean("dnssec_present", false)) "DNSSEC ✓" else "DNSSEC לא זוהה")
                append(" • HTTP headers חסרים ${jsonStrings(http.optJSONArray("missing_headers")).size}")
                tls.optString("version").takeIf { it.isNotBlank() }?.let { append(" • $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "התאמת CVE היא הערכת חשיפה מבוססת ראיות, לא הוכחה שניתן לנצל את החולשה. גרסה ותצורה מדויקות קובעות את הסטטוס הסופי.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AttackSurfaceCard(surface: JSONObject) {
    val score = surface.optInt("score", 0)
    val services = jsonObjects(surface.optJSONArray("services"))
    SectionCard {
        Text("חשיפה חיצונית", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("חשיפה", score.toString(), Modifier.weight(1f), riskAccentShared(score))
            MetricCard("שירותים", surface.optInt("public_service_count", services.size).toString(), Modifier.weight(1f))
            MetricCard("רגישים", surface.optInt("sensitive_service_count", 0).toString(), Modifier.weight(1f))
        }
        if (services.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            services.take(12).forEachIndexed { index, service ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                val port = service.optInt("port", 0)
                val name = service.optString("service", "service")
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                    Text(
                        "$port  •  $name",
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetTimelineCard(history: List<JSONObject>) {
    SectionCard {
        Text("ציר זמן", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "השוואה מהירה בין הסריקות האחרונות של אותו יעד.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        history.take(6).forEachIndexed { index, row ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
            }
            val risk = row.optInt("risk_score", 0)
            val findings = row.optInt("finding_count", 0)
            val changed = if (row.has("changed") && !row.isNull("changed")) row.optBoolean("changed") else null
            val date = timelineDate(row.optString("created_at").ifBlank { row.optString("updated_at") })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(date.ifBlank { "סריקה" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append("סיכון $risk • $findings ממצאים")
                            if (changed == true) append(" • שינוי זוהה")
                            if (changed == false) append(" • ללא שינוי")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(shape = RoundedCornerShape(999.dp), color = riskAccentShared(risk).copy(alpha = 0.15f)) {
                    Text(
                        risk.toString(),
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = riskAccentShared(risk),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationshipMapCard(rows: List<String>) {
    SectionCard {
        Text("מפת קשרים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "הקשרים המרכזיים בין היעד, כתובות IP, DNS, תעודות ורכיבים שנמצאו.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        rows.take(10).forEachIndexed { index, value ->
            if (index > 0) Spacer(Modifier.height(7.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
                Text(
                    value,
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun collectRelationshipRows(deep: JSONObject): List<String> {
    val root = deep.optJSONObject("relationships") ?: return emptyList()
    val labels = mutableMapOf<String, String>()
    jsonObjects(root.optJSONArray("entities")).forEach { entity ->
        val id = entity.optString("id").trim()
        val label = entity.optString("label").trim()
        if (id.isNotBlank() && label.isUsefulDisplayValue()) labels[id] = label
    }
    val out = linkedSetOf<String>()
    jsonObjects(root.optJSONArray("relationships")).forEach { relation ->
        val source = labels[relation.optString("source")]
        val target = labels[relation.optString("target")]
        if (source.isNullOrBlank() || target.isNullOrBlank()) return@forEach
        val kind = relation.optString("kind", "related").replace('_', ' ')
        val value = "$source  →  $kind  →  $target"
        if (value.length <= 500) out += value
    }
    return out.take(40)
}

private fun timelineDate(value: String): String {
    val clean = value.trim()
    if (clean.isBlank()) return ""
    val normalized = clean.replace(' ', 'T')
    val date = normalized.substringBefore('T')
    val time = normalized.substringAfter('T', "").take(5)
    return if (time.isBlank()) date else "$date • $time"
}

@Composable
private fun ResultOverviewCard(intelligence: IntelligenceSummary) {
    val riskColor = riskAccentShared(intelligence.riskScore)
    SectionCard {
        Text(
            intelligence.filename,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        intelligence.fileType?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(if (it == "target") "מודיעין יעד" else it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("סיכון", intelligence.riskScore.toString(), Modifier.weight(1f), riskColor)
            MetricCard("ממצאים", intelligence.findingCount.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(11.dp))
        Text(
            buildString {
                append("קריטי ${intelligence.critical}")
                append("  •  גבוה ${intelligence.high}")
                append("  •  בינוני ${intelligence.medium}")
                append("  •  נמוך ${intelligence.low}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FindingSummaryCard(finding: JSONObject, preferRiskScoreField: Boolean = false) {
    val title = finding.optString("title").ifBlank { finding.optString("type", "ממצא") }
    val severity = finding.optString("severity", "info")
    val risk = if (preferRiskScoreField && finding.has("risk_score")) finding.optInt("risk_score", 0) else findingRisk(finding)
    val description = listOf("description", "detail", "value", "match", "message").firstNotNullOfOrNull { key ->
        finding.optString(key).takeIf { it.isNotBlank() }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                SeverityPill(severity, risk)
            }
            description?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeverityPill(severity: String, risk: Int) {
    val color = riskAccentShared(risk)
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            severityLabel(severity),
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun searchableFinding(finding: JSONObject): String = buildString {
    append(finding.optString("title")); append(' ')
    append(finding.optString("description")); append(' ')
    append(finding.optString("severity")); append(' ')
    append(finding.optString("type")); append(' ')
    append(finding.optString("category"))
}.lowercase()

private fun findingRisk(finding: JSONObject): Int {
    val nested = finding.optJSONObject("risk")?.optInt("score", -1) ?: -1
    if (nested >= 0) return nested
    if (finding.has("risk_score")) return finding.optInt("risk_score", 0)
    return when (finding.optString("severity").lowercase()) {
        "critical" -> 95
        "high" -> 80
        "medium", "warning" -> 55
        "low" -> 25
        else -> 10
    }
}

private fun severityLabel(value: String): String = when (value.lowercase()) {
    "critical" -> "קריטי"
    "high" -> "גבוה"
    "medium", "warning" -> "בינוני"
    "low" -> "נמוך"
    else -> "מידע"
}

private fun collectUsefulAssets(intelligence: IntelligenceSummary): List<String> {
    val result = linkedSetOf<String>()
    val preferredKeys = listOf("label", "name", "domain", "host", "hostname", "ip", "address", "url", "value")
    intelligence.entities.forEach { entity ->
        val kind = entity.optString("kind").takeIf { it.isNotBlank() }
        val value = preferredKeys.firstNotNullOfOrNull { key ->
            entity.optString(key).trim().takeIf { it.isUsefulDisplayValue() }
        }
        if (value != null) result += if (kind != null) "$kind • $value" else value
    }

    val deepRelationships = intelligence.deepIntelligence.optJSONObject("relationships")
    jsonObjects(deepRelationships?.optJSONArray("entities")).forEach { entity ->
        val kind = entity.optString("kind").takeIf { it.isNotBlank() }
        val label = entity.optString("label").trim().takeIf { it.isUsefulDisplayValue() }
        if (label != null) result += if (kind != null) "$kind • $label" else label
    }
    return result.take(150)
}

private fun collectUsefulRows(intelligence: IntelligenceSummary): List<InfoRow> {
    val rows = mutableListOf<InfoRow>()
    val seen = mutableSetOf<String>()

    fun add(path: List<String>, raw: String) {
        val value = raw.trim()
        if (!value.isUsefulDisplayValue()) return
        val label = friendlyLabel(path)
        val key = "$label\u0000$value"
        if (seen.add(key)) rows += InfoRow(label, value)
    }

    fun walk(value: Any?, path: List<String>, depth: Int) {
        if (rows.size >= 120 || depth > 5 || value == null || value == JSONObject.NULL) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                keys.forEach { key -> if (!skipKey(key)) walk(value.opt(key), path + key, depth + 1) }
            }
            is JSONArray -> {
                if (value.length() == 0) return
                val primitiveValues = mutableListOf<String>()
                var hasObjects = false
                for (i in 0 until minOf(value.length(), 24)) {
                    val item = value.opt(i)
                    if (item is JSONObject || item is JSONArray) {
                        hasObjects = true
                        walk(item, path, depth + 1)
                    } else if (item != null && item != JSONObject.NULL) {
                        val text = item.toString().trim()
                        if (text.isUsefulDisplayValue()) primitiveValues += text
                    }
                }
                if (!hasObjects && primitiveValues.isNotEmpty()) add(path, primitiveValues.distinct().take(12).joinToString(", "))
            }
            is Boolean -> add(path, if (value) "כן" else "לא")
            is Number -> add(path, value.toString())
            else -> add(path, value.toString())
        }
    }

    val deepTarget = intelligence.deepIntelligence.optJSONObject("target")
    if (deepTarget != null) walk(deepTarget, listOf("target"), 0)
    val fileArtifact = intelligence.fileIntelligence.optJSONObject("artifact")
    if (fileArtifact != null) walk(fileArtifact, listOf("artifact"), 0)
    walk(intelligence.metadata, listOf("metadata"), 0)
    walk(intelligence.modules, listOf("modules"), 0)
    return rows.take(90)
}

private fun skipKey(key: String): Boolean {
    val k = key.lowercase()
    return k in setOf(
        "id", "job_id", "sha256", "manifest_sha256", "schema", "created_at", "updated_at",
        "evidence_ids", "raw", "trace", "debug", "stack", "source_path", "object_path",
        "input", "modules", "network_active", "scan_mode", "started_at", "completed_at", "duration_ms",
        "available", "error"
    ) || k.endsWith("_id") || k.endsWith("_sha256")
}

private fun friendlyLabel(path: List<String>): String {
    val key = path.lastOrNull()?.lowercase().orEmpty()
    val translated = when (key) {
        "title" -> "כותרת"
        "status" -> "סטטוס"
        "status_code" -> "קוד HTTP"
        "final_url", "url" -> "כתובת סופית"
        "domain", "target" -> "דומיין / יעד"
        "target_kind" -> "סוג יעד"
        "host", "hostname" -> "מארח"
        "ip", "address", "resolved_ip", "ips", "resolved_ips" -> "כתובות IP"
        "country" -> "מדינה"
        "city" -> "עיר"
        "organization", "org", "owner" -> "ארגון"
        "registrar" -> "רשם דומיין"
        "server" -> "שרת Web"
        "content_type" -> "סוג תוכן"
        "content_length" -> "גודל תוכן"
        "technology", "technologies", "tech" -> "טכנולוגיות"
        "ports", "open_ports", "services" -> "שירותים / פורטים"
        "issuer", "issuer_display" -> "מנפיק תעודה"
        "subject", "subject_display" -> "בעל התעודה"
        "expires", "expires_at", "expiry", "not_after" -> "תוקף תעודה / רישום"
        "created" -> "נוצר"
        "updated" -> "עודכן"
        "days_until_expiry" -> "ימים עד פקיעה"
        "tls_version", "protocol", "version" -> "גרסת TLS"
        "cipher" -> "הצפנה"
        "nameservers", "name_servers", "ns" -> "שרתי DNS"
        "mx" -> "שרתי דואר"
        "a" -> "רשומות A"
        "aaaa" -> "רשומות AAAA"
        "cname" -> "CNAME"
        "txt" -> "רשומות TXT"
        "redirects", "redirect_count" -> "הפניות"
        "reputation" -> "מוניטין"
        "risk", "risk_score", "overall_score" -> "ציון סיכון"
        "summary", "overview" -> "סיכום"
        "size", "size_bytes" -> "גודל"
        "file_type", "type" -> "סוג"
        "package", "package_name" -> "שם חבילה"
        "version_name" -> "גרסה"
        "permissions" -> "הרשאות"
        "http_version" -> "גרסת HTTP"
        "http_status" -> "סטטוס HTTP"
        "secure" -> "Secure"
        "http_only" -> "HttpOnly"
        "same_site" -> "SameSite"
        "missing" -> "חסר"
        "missing_count" -> "כותרות אבטחה חסרות"
        "present_count" -> "כותרות אבטחה קיימות"
        else -> key.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    return translated.ifBlank { "מידע" }
}

private fun String.isUsefulDisplayValue(): Boolean {
    val text = trim()
    if (text.isBlank() || text == "null" || text == "{}" || text == "[]") return false
    if (text.length > 700) return false
    if (Regex("^[a-fA-F0-9]{32,}$").matches(text)) return false
    if (Regex("^[0-9a-fA-F-]{32,}$").matches(text) && text.count { it == '-' } >= 4) return false
    return true
}

private fun jsonObjects(arr: JSONArray?): List<JSONObject> = buildList {
    if (arr == null) return@buildList
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
}

private fun jsonStrings(arr: JSONArray?): List<String> = buildList {
    if (arr == null) return@buildList
    for (i in 0 until arr.length()) {
        val value = arr.optString(i).trim()
        if (value.isNotBlank()) add(value)
    }
}

fun riskAccentShared(score: Int): Color = when {
    score >= 90 -> Color(0xFFFF5A67)
    score >= 70 -> Color(0xFFFF8A4C)
    score >= 45 -> Color(0xFFFFC857)
    else -> Color(0xFF6EE7A8)
}
