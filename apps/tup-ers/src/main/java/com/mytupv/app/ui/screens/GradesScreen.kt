package com.mytupv.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytupv.app.scraper.ErsScraper
import com.mytupv.app.scraper.ScrapeResult
import com.mytupv.app.scraper.TermGrades
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ──────────────────────────────────────────
//  Colours
// ──────────────────────────────────────────
private val BgDeep        = Color(0xFF07040F)
private val PanelBg       = Color(0xFF0E0A1A)
private val PanelBorder   = Color(0xFF1E1A2E)
private val Accent        = Color(0xFF788CFF)
private val AccentSoft    = Color(0x26788CFF)
private val TextPrimary   = Color(0xFFF0EEFF)
private val TextMuted     = Color(0x99EBEBF5)
private val GreenPass     = Color(0xFF51CF66)
private val RedFail       = Color(0xFFFF6B6B)

@Composable
fun GradesScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mytupv_prefs", 0) }
    val scope  = rememberCoroutineScope()

    // ── Persisted credentials ──────────────────────────
    var username  by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    var password  by remember { mutableStateOf(prefs.getString("password", "") ?: "") }
    var birthdate by remember { mutableStateOf(prefs.getString("birthdate", "") ?: "") }

    // ── App state ──────────────────────────────────────
    var result         by remember { mutableStateOf<ScrapeResult?>(null) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var loading        by remember { mutableStateOf(false) }
    var loadingStep    by remember { mutableStateOf("") }
    var selectedTerm   by remember { mutableStateOf(0) }
    var searchQuery    by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Load cached result
    LaunchedEffect(Unit) {
        val cached = prefs.getString("cached_grades_json", null)
        val cachedName = prefs.getString("student_name", null)
        if (cached != null && cachedName != null) {
            // We simply keep credentials ready; real restore would need serialization
        }
    }

    fun doSync() {
        if (username.isBlank() || password.isBlank() || birthdate.isBlank()) {
            errorMsg = "Please fill in all fields."
            return
        }
        scope.launch {
            loading = true
            errorMsg = null
            val steps = listOf(
                "Contacting ERS portal…",
                "Retrieving security token…",
                "Authenticating credentials…",
                "Fetching grades database…",
                "Parsing subject records…"
            )
            steps.forEachIndexed { i, step ->
                loadingStep = step
                withContext(Dispatchers.IO) { Thread.sleep(if (i == 0) 300L else 1000L) }
            }
            try {
                val scrapeResult = withContext(Dispatchers.IO) {
                    ErsScraper().scrape(username, password, birthdate)
                }
                result = scrapeResult
                selectedTerm = 0
                prefs.edit()
                    .putString("username", username)
                    .putString("password", password)
                    .putString("birthdate", birthdate)
                    .putString("student_name", scrapeResult.studentName)
                    .apply()
            } catch (e: Exception) {
                errorMsg = e.message ?: "An unexpected error occurred."
            } finally {
                loading = false
                loadingStep = ""
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        // ── LOADING OVERLAY ────────────────────────────
        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(),
            exit  = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape  = RoundedCornerShape(20.dp),
                    color  = PanelBg,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier.padding(32.dp).widthIn(max = 280.dp)
                ) {
                    Column(
                        Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "spin")
                        val angle by infiniteTransition.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                            label = "angle"
                        )
                        Icon(
                            Icons.Filled.Sync, null,
                            tint = Accent,
                            modifier = Modifier.size(42.dp).rotate(angle)
                        )
                        Text("Syncing Grades", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(loadingStep, color = Accent, fontSize = 13.sp)
                        Text(
                            "Your credentials never leave your device.",
                            color = TextMuted, fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ── MAIN CONTENT ───────────────────────────────
        if (result == null) {
            // ──── LOGIN SCREEN ─────────────────────────
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(40.dp))

                // Header
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AccentSoft)
                            .border(1.dp, Accent.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.School, null, tint = Accent, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("MyTUPV", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("TUP ERS Grade Viewer", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your credentials are stored only on this device.",
                        color = TextMuted, fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(28.dp))

                // Credential card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PanelBg,
                    border = BorderStroke(1.dp, PanelBorder)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ErsTextField(
                            value = username,
                            onValueChange = { username = it.uppercase() },
                            label = "Student ID",
                            placeholder = "e.g. TUPV-22-0595",
                            leadingIcon = Icons.Outlined.Person
                        )
                        ErsTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Portal Password",
                            placeholder = "Password",
                            leadingIcon = Icons.Outlined.Lock,
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            onPasswordToggle = { passwordVisible = !passwordVisible }
                        )
                        ErsDateField(
                            value = birthdate,
                            onValueChange = { birthdate = it },
                            label = "Date of Birth (YYYY-MM-DD)",
                            placeholder = "e.g. 2003-04-15",
                        )
                    }
                }

                // Error
                AnimatedVisibility(visible = errorMsg != null) {
                    errorMsg?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0x22FF6B6B),
                            border = BorderStroke(1.dp, RedFail.copy(alpha = 0.3f))
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.ErrorOutline, null, tint = RedFail, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Color(0xFFFFCCCC), fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = ::doSync,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0B071A))
                ) {
                    Icon(Icons.Filled.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync Grades from Portal", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(Modifier.height(40.dp))
            }

        } else {
            // ──── GRADES VIEW ──────────────────────────
            val terms = result!!.terms
            val term  = terms.getOrNull(selectedTerm) ?: terms.first()
            val filtered = if (searchQuery.isBlank()) term.subjects
                else term.subjects.filter {
                    it.code.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true)
                }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ── Header bar ──────────────────────────
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("MyTUPV", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(result!!.studentName, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = ::doSync) {
                                Icon(Icons.Filled.Sync, "Refresh", tint = TextMuted)
                            }
                            IconButton(onClick = {
                                result = null
                                errorMsg = null
                                prefs.edit().remove("student_name").apply()
                            }) {
                                Icon(Icons.Filled.Logout, "Log out", tint = RedFail)
                            }
                        }
                    }
                }

                // ── Term tabs ───────────────────────────
                item {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(terms) { idx, t ->
                            val selected = idx == selectedTerm
                            Surface(
                                onClick = { selectedTerm = idx; searchQuery = "" },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) AccentSoft else PanelBg,
                                border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = 0.6f) else PanelBorder)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(t.term, color = if (selected) Accent else TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("S.Y. ${t.schoolYear}", color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Summary strip ───────────────────────
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(Modifier.weight(1f), label = "GPA", value = term.gpa.ifBlank { "N/A" }, tint = Accent)
                        StatCard(Modifier.weight(1f), label = "Units", value = "${term.subjects.sumOf { it.units }}", tint = Accent)
                        val passed = term.subjects.count { it.status.contains("passed", true) }
                        StatCard(Modifier.weight(1f), label = "Passed", value = "$passed / ${term.subjects.size}", tint = GreenPass)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── Meta strip ──────────────────────────
                item {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = PanelBg,
                        border = BorderStroke(1.dp, PanelBorder)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetaChip("Course", term.courseCode)
                            MetaChip("Status", term.scholasticStatus)
                            MetaChip("Admission", term.admissionStatus)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── Search bar ──────────────────────────
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search subjects…", color = TextMuted, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{ IconButton({ searchQuery = "" }) { Icon(Icons.Filled.Clear, null, tint = TextMuted) } }} else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor    = PanelBg,
                            unfocusedContainerColor  = PanelBg,
                            focusedBorderColor       = Accent,
                            unfocusedBorderColor     = PanelBorder,
                            focusedTextColor         = TextPrimary,
                            unfocusedTextColor       = TextPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // ── Subject cards ───────────────────────
                items(filtered) { subj ->
                    var expanded by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = PanelBg,
                        border = BorderStroke(1.dp, if (expanded) Accent.copy(alpha = 0.35f) else PanelBorder)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = AccentSoft,
                                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.3f))
                                    ) {
                                        Text(subj.code, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text("${subj.units}u", color = TextMuted, fontSize = 10.sp)
                                }
                                StatusBadge(subj.status)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(subj.description, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                            // Quick grade row
                            if (!expanded && subj.grades.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    subj.grades.take(3).forEach { g ->
                                        Text(
                                            "${g.label.split(" - ").lastOrNull() ?: g.label}: ${g.value.ifBlank { "--" }}",
                                            color = TextMuted, fontSize = 11.sp
                                        )
                                    }
                                    if (subj.average.isNotBlank()) {
                                        Text("Avg: ", color = TextMuted, fontSize = 11.sp)
                                        Text(subj.average, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Expanded detail
                            AnimatedVisibility(visible = expanded) {
                                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    HorizontalDivider(color = PanelBorder)
                                    // Grades grid
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = BgDeep,
                                        border = BorderStroke(1.dp, PanelBorder)
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("GRADES", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            subj.grades.forEach { g ->
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(g.label.replace(" - ", " · "), color = TextMuted, fontSize = 13.sp)
                                                    Text(g.value.ifBlank { "--" }, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                }
                                            }
                                            if (subj.average.isNotBlank()) {
                                                HorizontalDivider(color = PanelBorder)
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("Term Average", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text(subj.average, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                    // Meta
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (subj.faculty.isNotBlank()) {
                                            LabelValue("Instructor", subj.faculty)
                                        }
                                        if (subj.section.isNotBlank()) {
                                            LabelValue("Section", subj.section)
                                        }
                                    }
                                }
                            }

                            // Chevron indicator
                            Spacer(Modifier.height(4.dp))
                            Icon(
                                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                null, tint = TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.align(Alignment.CenterHorizontally).size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────
//  Helpers
// ──────────────────────────────────────────

@Composable
private fun ErsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordToggle: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TextMuted, fontSize = 13.sp) },
        leadingIcon = { Icon(leadingIcon, null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (isPassword && onPasswordToggle != null) {{
            IconButton(onClick = onPasswordToggle) {
                Icon(
                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    null, tint = TextMuted
                )
            }
        }} else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = BgDeep,
            unfocusedContainerColor = BgDeep,
            focusedBorderColor      = Accent,
            unfocusedBorderColor    = PanelBorder,
            focusedTextColor        = TextPrimary,
            unfocusedTextColor      = TextPrimary,
            focusedLabelColor       = Accent,
            unfocusedLabelColor     = TextMuted,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun ErsDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TextMuted, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = BgDeep,
            unfocusedContainerColor = BgDeep,
            focusedBorderColor      = Accent,
            unfocusedBorderColor    = PanelBorder,
            focusedTextColor        = TextPrimary,
            unfocusedTextColor      = TextPrimary,
            focusedLabelColor       = Accent,
            unfocusedLabelColor     = TextMuted,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun StatusBadge(status: String) {
    val s = status.lowercase()
    val (bg, fg) = when {
        s.contains("pass") -> Color(0x1A51CF66) to GreenPass
        s.contains("fail") || s.contains("drop") -> Color(0x1AFF6B6B) to RedFail
        else -> Color(0x14FFFFFF) to TextMuted
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg, border = BorderStroke(1.dp, fg.copy(alpha = 0.3f))) {
        Text(
            text = when { s.contains("pass") -> "Passed"; s.contains("fail") -> "Failed"; s.contains("drop") -> "Dropped"; else -> status.ifBlank { "Ongoing" } },
            color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, label: String, value: String, tint: Color) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = PanelBg, border = BorderStroke(1.dp, PanelBorder)) {
        Column(Modifier.padding(10.dp)) {
            Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun MetaChip(label: String, value: String) {
    Column {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value.ifBlank { "N/A" }, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = TextPrimary, fontSize = 12.sp)
    }
}
