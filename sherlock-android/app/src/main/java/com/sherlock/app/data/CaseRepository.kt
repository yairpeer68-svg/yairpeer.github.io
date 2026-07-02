package com.sherlock.app.data

import android.content.Context
import com.sherlock.app.data.db.AppDatabase
import com.sherlock.app.data.db.CaseEntity
import com.sherlock.app.data.db.FindingEntity
import com.sherlock.app.data.db.SubjectEntity
import com.sherlock.app.data.db.SubjectType
import com.sherlock.app.data.osint.OsintEngine
import com.sherlock.app.data.osint.OsintFinding
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for the investigation system: manages cases and subjects,
 * runs the right OSINT source per subject type, stores findings, and cross-references
 * results by extracting new identifiers and linking them back into the case.
 */
class CaseRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val caseDao = db.caseDao()
    private val subjectDao = db.subjectDao()
    private val findingDao = db.findingDao()

    private val usernameSearch = SearchRepository(context)
    private val osint = OsintEngine()

    // ---- cases ----
    fun observeCases(): Flow<List<CaseEntity>> = caseDao.observeAll()
    fun observeCase(id: Long): Flow<CaseEntity?> = caseDao.observe(id)
    suspend fun createCase(name: String, description: String): Long =
        caseDao.insert(CaseEntity(name = name.ifBlank { "Untitled case" }, description = description))
    suspend fun deleteCase(id: Long) = caseDao.delete(id)

    // ---- subjects ----
    fun observeSubjects(caseId: Long): Flow<List<SubjectEntity>> = subjectDao.observeForCase(caseId)

    suspend fun addSubject(
        caseId: Long,
        type: SubjectType,
        value: String,
        note: String = "",
        origin: String = "manual",
        derivedFromFindingId: Long? = null
    ): Long {
        val v = value.trim()
        if (v.isBlank()) return -1
        if (subjectDao.exists(caseId, type.name, v) > 0) return -1
        val id = subjectDao.insert(
            SubjectEntity(
                caseId = caseId, type = type.name, value = v,
                note = note, origin = origin, derivedFromFindingId = derivedFromFindingId
            )
        )
        caseDao.touch(caseId, System.currentTimeMillis())
        return id
    }

    suspend fun deleteSubject(id: Long) {
        findingDao.deleteForSubject(id)
        subjectDao.delete(id)
    }

    // ---- findings ----
    fun observeFindings(caseId: Long): Flow<List<FindingEntity>> = findingDao.observeForCase(caseId)
    fun observeSubjectFindings(subjectId: Long): Flow<List<FindingEntity>> =
        findingDao.observeForSubject(subjectId)
    suspend fun getFindings(caseId: Long): List<FindingEntity> = findingDao.getForCase(caseId)

    // ---- investigation ----

    /**
     * Runs the appropriate source(s) for [subject], stores hits, then cross-references.
     * Returns the number of findings stored. Reports 0..1 progress for long scans.
     */
    suspend fun investigate(subject: SubjectEntity, onProgress: (Float) -> Unit = {}): Int {
        findingDao.deleteForSubject(subject.id)
        val results: List<OsintFinding> = when (subject.subjectType) {
            SubjectType.USERNAME -> usernameFindings(subject.value, onProgress)
            SubjectType.EMAIL -> osint.email(subject.value)
            SubjectType.DOMAIN -> osint.domain(subject.value)
            SubjectType.IP -> osint.ip(subject.value)
            SubjectType.PHONE -> osint.phone(subject.value)
            SubjectType.NAME -> nameFindings(subject.value)
            SubjectType.IMAGE -> emptyList()
        }
        val ids = results.map { f ->
            findingDao.insert(
                FindingEntity(
                    caseId = subject.caseId, subjectId = subject.id,
                    source = f.source, category = f.category, title = f.title,
                    detail = f.detail, url = f.url, positive = f.positive
                )
            ) to f
        }
        subjectDao.markInvestigated(subject.id, true)
        crossReference(subject, ids)
        caseDao.touch(subject.caseId, System.currentTimeMillis())
        onProgress(1f)
        return results.size
    }

    private suspend fun usernameFindings(username: String, onProgress: (Float) -> Unit): List<OsintFinding> {
        val total = usernameSearch.getSiteCount().coerceAtLeast(1)
        var done = 0
        val hits = mutableListOf<OsintFinding>()
        usernameSearch.searchUsername(username).collect { r ->
            done++
            onProgress(done.toFloat() / total)
            if (r.found) {
                hits += OsintFinding(
                    source = r.site.name, category = r.site.category,
                    title = "Profile on ${r.site.name}", detail = r.profileUrl,
                    url = r.profileUrl, positive = true
                )
            }
        }
        hits.add(0, OsintFinding("Summary", "Username", "${hits.size} profiles found", "across $total platforms", positive = hits.isNotEmpty()))
        return hits
    }

    private fun nameFindings(name: String): List<OsintFinding> {
        val q = name.trim().replace(" ", "+")
        val quoted = "\"" + name.trim() + "\""
        return listOf(
            OsintFinding("Google", "Name", "Google search", quoted, "https://www.google.com/search?q=$q", true),
            OsintFinding("LinkedIn", "Name", "LinkedIn people search", name, "https://www.linkedin.com/search/results/people/?keywords=$q", true),
            OsintFinding("Facebook", "Name", "Facebook people search", name, "https://www.facebook.com/search/people/?q=$q", true),
            OsintFinding("Google", "Name", "Documents (PDF/DOC)", "filetype dork", "https://www.google.com/search?q=$q+filetype:pdf+OR+filetype:doc", true)
        )
    }

    // ---- cross-reference: derive new subjects from findings ----

    private suspend fun crossReference(parent: SubjectEntity, stored: List<Pair<Long, OsintFinding>>) {
        var derived = 0
        for ((findingId, f) in stored) {
            if (derived >= MAX_DERIVED) break
            for ((type, value) in extract(f, parent.subjectType)) {
                val v = value.trim().trimEnd('.')
                if (v.isBlank()) continue
                if (type == parent.subjectType && v.equals(parent.value, true)) continue
                val id = addSubject(
                    caseId = parent.caseId, type = type, value = v,
                    origin = "derived:${f.source}", derivedFromFindingId = findingId
                )
                if (id > 0 && ++derived >= MAX_DERIVED) break
            }
        }
    }

    /** Pulls candidate identifiers out of a single finding, aware of its source/type. */
    private fun extract(f: OsintFinding, parentType: SubjectType): List<Pair<SubjectType, String>> {
        val out = mutableListOf<Pair<SubjectType, String>>()
        val text = "${f.detail} ${f.url}"

        // Emails anywhere in the text.
        EMAIL_RE.findAll(text).forEach { out += SubjectType.EMAIL to it.value.lowercase() }

        // Domain-source specifics.
        if (f.category == "Domain") {
            when {
                f.title.startsWith("A record") || f.title.startsWith("AAAA record") ->
                    IP_RE.find(f.detail)?.let { out += SubjectType.IP to it.value }
                f.title.startsWith("MX record") ->
                    HOST_RE.find(f.detail)?.let { out += SubjectType.DOMAIN to registrable(it.value) }
                f.title == "Mail domain" -> out += SubjectType.DOMAIN to f.detail
            }
        }
        // Email → linked username.
        if (f.category == "Email" && f.title == "Linked username") {
            out += SubjectType.USERNAME to f.detail
        }
        // IPs mentioned in any detail.
        if (parentType != SubjectType.IP) {
            IP_RE.findAll(f.detail).forEach { out += SubjectType.IP to it.value }
        }
        return out
    }

    private fun registrable(host: String): String {
        val parts = host.trim('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    companion object {
        private const val MAX_DERIVED = 12
        private val EMAIL_RE = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        private val IP_RE = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        private val HOST_RE = Regex("[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+")
    }
}
