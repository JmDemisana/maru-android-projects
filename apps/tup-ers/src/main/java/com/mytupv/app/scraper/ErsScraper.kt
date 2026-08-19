package com.mytupv.app.scraper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.CopyOnWriteArrayList

// ──────────────────────────────────────────────────────────
//  Data Models
// ──────────────────────────────────────────────────────────

data class SubjectGrade(
    val code: String,
    val description: String,
    val faculty: String,
    val units: Int,
    val section: String,
    val grades: List<GradeEntry>,
    val average: String,
    val status: String,
)

data class GradeEntry(val label: String, val value: String)

data class TermGrades(
    val schoolYear: String,
    val term: String,
    val admissionStatus: String,
    val scholasticStatus: String,
    val courseCode: String,
    val courseDescription: String,
    val gpa: String,
    val subjects: List<SubjectGrade>,
)

data class ScrapeResult(
    val studentName: String,
    val terms: List<TermGrades>,
)

// ──────────────────────────────────────────────────────────
//  In-memory CookieJar so the session persists across calls
// ──────────────────────────────────────────────────────────

private class MemoryCookieJar : CookieJar {
    private val store = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { existing -> cookies.any { it.name == existing.name } }
        store.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()

    fun clear() = store.clear()
}

// ──────────────────────────────────────────────────────────
//  ErsScraper
// ──────────────────────────────────────────────────────────

class ErsScraper {

    private val cookieJar = MemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .build()

    private val baseUrl = "https://ers.tup.edu.ph"

    /** Convert YYYY-MM-DD (from date picker) → MM/DD/YYYY (portal format) */
    private fun formatBirthdate(raw: String): String {
        val parts = raw.split("-")
        return if (parts.size == 3) "${parts[1]}/${parts[2]}/${parts[0]}" else raw
    }

    /** GET request, returns response body as String */
    private fun get(path: String): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", "Mozilla/5.0 (Android; MyTUPV)")
            .build()
        client.newCall(request).execute().use { return it.body?.string() ?: "" }
    }

    /** POST request, returns response body as String */
    private fun post(path: String, fields: Map<String, String>): String {
        val formBuilder = FormBody.Builder()
        fields.forEach { (k, v) -> formBuilder.add(k, v) }
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", "Mozilla/5.0 (Android; MyTUPV)")
            .header("Referer", "$baseUrl/aims/students/")
            .post(formBuilder.build())
            .build()
        client.newCall(request).execute().use { return it.body?.string() ?: "" }
    }

    // ──────────────────────────────────────────────────────
    //  Parse flat header list from a table, resolving
    //  rowspan / colspan attributes
    // ──────────────────────────────────────────────────────

    private fun getTableHeaders(table: Element): List<String> {
        val rows = table.select("tr").filter { it.select("th").isNotEmpty() }
        if (rows.isEmpty()) return emptyList()

        var maxCols = 0
        rows.forEach { row ->
            maxCols = maxOf(maxCols, row.select("th").sumOf { it.attr("colspan").toIntOrNull() ?: 1 })
        }

        val grid = Array(rows.size) { Array(maxCols) { "" } }

        rows.forEachIndexed { rowIdx, row ->
            var colIdx = 0
            row.select("th").forEach { th ->
                val colspan = th.attr("colspan").toIntOrNull() ?: 1
                val rowspan = th.attr("rowspan").toIntOrNull() ?: 1
                val text = th.text().trim()

                while (colIdx < maxCols && grid[rowIdx][colIdx].isNotEmpty()) colIdx++

                for (r in 0 until rowspan) {
                    for (c in 0 until colspan) {
                        if (rowIdx + r < grid.size && colIdx + c < maxCols)
                            grid[rowIdx + r][colIdx + c] = text
                    }
                }
                colIdx += colspan
            }
        }

        return (0 until maxCols).map { col ->
            (0 until rows.size)
                .mapNotNull { row -> grid[row][col].takeIf { it.isNotBlank() && it != "#" } }
                .distinct()
                .joinToString(" - ")
        }
    }

    // ──────────────────────────────────────────────────────
    //  Parse grades page HTML → TermGrades list
    // ──────────────────────────────────────────────────────

    private fun parseGrades(html: String): List<TermGrades> {
        val doc = Jsoup.parse(html)
        val terms = mutableListOf<TermGrades>()
        val tables = doc.select("table")

        var currentMeta: MutableMap<String, String>? = null

        tables.forEach { table ->
            val text = table.text()

            // ── Header table (contains SCHOOL YEAR) ──────
            if (text.contains("SCHOOL YEAR", ignoreCase = true)) {
                currentMeta = mutableMapOf()
                table.select("tr").forEach { row ->
                    val cells = row.select("td").map { it.text().trim() }
                    for (i in cells.indices) {
                        when {
                            cells[i].contains("SCHOOL YEAR", true) -> currentMeta!!["schoolYear"] = cells.getOrElse(i + 1) { "" }
                            cells[i].equals("Term", ignoreCase = true)   -> currentMeta!!["term"]  = cells.getOrElse(i + 1) { "" }
                            cells[i].contains("Admission Status", true)  -> currentMeta!!["admissionStatus"]  = cells.getOrElse(i + 1) { "" }
                            cells[i].contains("Scholastic Status", true) -> currentMeta!!["scholasticStatus"] = cells.getOrElse(i + 1) { "" }
                            cells[i].contains("Course Code", true)       -> currentMeta!!["courseCode"]        = cells.getOrElse(i + 1) { "" }
                            cells[i].contains("Course Description", true) -> currentMeta!!["courseDescription"] = cells.getOrElse(i + 1) { "" }
                            cells[i].contains("GPA", true) -> {
                                val gpaMatch = Regex("([\\d.]+)").find(cells.getOrElse(i + 1) { "" })
                                currentMeta!!["gpa"] = gpaMatch?.value ?: ""
                            }
                        }
                    }
                }
            }

            // ── Subjects table ────────────────────────────
            if (text.contains("Subject Code", ignoreCase = true) && currentMeta != null) {
                val headers = getTableHeaders(table)
                val subjects = mutableListOf<SubjectGrade>()

                table.select("tr").filter { it.select("th").isEmpty() }.forEach { row ->
                    val cells = row.select("td").map { it.text().trim() }
                    if (cells.isEmpty()) return@forEach

                    var code = ""; var description = ""; var faculty = ""
                    var units = 0; var section = ""; var average = ""; var status = ""
                    val gradeEntries = mutableListOf<GradeEntry>()

                    cells.forEachIndexed { idx, cell ->
                        val h = headers.getOrElse(idx) { "" }.uppercase()
                        when {
                            h.contains("SUBJECT CODE") || h.contains("SUBJ CODE") -> code = cell
                            h.contains("DESCRIPTION")  -> description = cell
                            h.contains("FACULTY")      -> faculty = cell
                            h.contains("UNITS")        -> units = cell.toIntOrNull() ?: 0
                            h.contains("SECTION")      -> section = cell
                            h.contains("STATUS")       -> status = cell
                            h.contains("AVERAGE")      -> average = cell
                            h.contains("PRELIM") || h.contains("MIDTERM") ||
                            h.contains("ENDTERM") || h.contains("FINAL") ->
                                gradeEntries.add(GradeEntry(headers[idx], cell))
                        }
                    }

                    if (code.isNotBlank() && description.isNotBlank()) {
                        subjects.add(SubjectGrade(code, description, faculty, units, section, gradeEntries, average, status))
                    }
                }

                terms.add(
                    TermGrades(
                        schoolYear = currentMeta!!["schoolYear"] ?: "",
                        term = currentMeta!!["term"] ?: "",
                        admissionStatus = currentMeta!!["admissionStatus"] ?: "",
                        scholasticStatus = currentMeta!!["scholasticStatus"] ?: "",
                        courseCode = currentMeta!!["courseCode"] ?: "",
                        courseDescription = currentMeta!!["courseDescription"] ?: "",
                        gpa = currentMeta!!["gpa"] ?: "",
                        subjects = subjects,
                    )
                )
                currentMeta = null
            }
        }
        return terms
    }

    // ──────────────────────────────────────────────────────
    //  Public: login + scrape
    // ──────────────────────────────────────────────────────

    fun scrape(username: String, password: String, birthdateRaw: String): ScrapeResult {
        cookieJar.clear()

        // 1. Fetch login page → extract CSRF token
        val loginPage = get("/aims/students/")
        val token = Regex("""name="_token"\s+value="([^"]+)"""").find(loginPage)?.groupValues?.get(1)
            ?: throw Exception("Could not retrieve security token. Check your internet connection.")

        // 2. POST credentials
        val bdate = formatBirthdate(birthdateRaw)
        post("/aims/process/new.validate.php", mapOf(
            "_token"   to token,
            "usertype" to "1",
            "username" to username,
            "password" to password,
            "bdate"    to bdate,
        ))

        // 3. Fetch grades page
        val gradesHtml = get("/aims/students/grades.php?mainID=106&menuDesc=Grades")

        // 4. Detect login failure
        if (gradesHtml.contains("frmLogin") || gradesHtml.contains("User Authentication")) {
            throw Exception("Invalid credentials. Please check your Student ID, password, or birthdate.")
        }

        // 5. Parse student name
        val studentName = Regex("""Welcome,\s*([^<(]+)""").find(gradesHtml)?.groupValues?.get(1)?.trim()
            ?: username

        // 6. Parse terms
        val terms = parseGrades(gradesHtml)
        if (terms.isEmpty()) throw Exception("No grades found. Your records may not be available yet.")

        return ScrapeResult(studentName = studentName, terms = terms)
    }
}
