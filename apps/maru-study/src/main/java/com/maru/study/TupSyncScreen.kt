package com.maru.study

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

private const val ERS_ORIGIN = "https://ers.tup.edu.ph"
private const val ERS_LOGIN_URL = "$ERS_ORIGIN/aims/students/"
private const val ERS_TIMEOUT_MS = 120_000L

private data class ErsRequest(
    val credentials: ErsCredentials,
    val mode: ErsSyncMode,
)

/**
 * Dedicated TUP Sync tab. It only reads from ERS and stages the result. The
 * schedule and grades are replaced solely when the user reviews the staged
 * import and confirms it in the native sheet.
 */
@Composable
internal fun TupSyncScreen(
    snapshot: StudySnapshot,
    onReview: (StudyChangeProposal) -> Unit,
    tab: ErsSyncTab,
    onOpenCredentials: () -> Unit,
    onSynced: (hasSchedule: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ErsCredentialStore(context) }
    val saved = remember { store.load() }
    val currentOnReview by rememberUpdatedState(onReview)
    val currentOnSynced by rememberUpdatedState(onSynced)

    val cache = remember { ErsSyncCache(context) }
    val cached = remember { cache.load() }
    val viewPreferences = remember { ViewPreferences(context) }

    var studentId by rememberSaveable { mutableStateOf(saved.studentId) }
    var password by remember { mutableStateOf(saved.password) }
    var birthday by rememberSaveable { mutableStateOf(saved.birthday) }
    var rememberLogin by rememberSaveable { mutableStateOf(store.rememberLogin()) }
    var scheduleView by rememberSaveable { mutableStateOf(viewPreferences.scheduleView()) }

    var passwordVisible by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf(cached?.result) }
    var stagedAt by remember { mutableStateOf(cached?.syncedAt ?: 0L) }

    val request = remember { AtomicReference<ErsRequest?>(null) }
    val payloadSink = remember { AtomicReference<((String) -> Unit)?>(null) }
    val bridge = remember { MaruErsBridge(payloadSink) }
    val lastInjectedUrl = remember { AtomicReference<String?>(null) }

    fun finish(raw: String) {
        if (!running) return
        val current = request.get() ?: return
        payloadSink.set(null)

        val result = runCatching { parseErsPayload(raw, current.mode) }.getOrElse { throwable ->
            running = false
            status = ""
            error = throwable.message ?: "Could not import from ERS."
            return
        }

        if (result.isEmpty()) {
            running = false
            status = ""
            error = when (current.mode) {
                ErsSyncMode.SCHEDULE -> "No schedule subjects were found for that account."
                ErsSyncMode.GRADES -> "No grades were found for that account."
                ErsSyncMode.BOTH -> "No schedule or grades were found for that account."
            }
            return
        }

        runCatching { store.save(current.credentials, rememberLogin) }
        runCatching { cache.save(raw) }

        running = false
        status = ""
        clearErsCookies()
        staged = result
        stagedAt = System.currentTimeMillis()
        currentOnSynced(result.schedule.isNotEmpty())
    }

    fun start() {
        error = null
        val credentials = ErsCredentials(
            studentId = studentId.trim(),
            password = password,
            birthday = birthday.trim(),
        )
        birthday = credentials.birthday

        if (!credentials.isComplete) {
            error = "Fill in your student ID, password, and birthday first."
            return
        }

        staged = null
        stagedAt = 0L
        lastInjectedUrl.set(null)
        request.set(ErsRequest(credentials, ErsSyncMode.BOTH))
        payloadSink.set { raw -> finish(raw) }
        running = true
        status = "Signing in to ERS…"
        clearErsCookies()
    }

    fun applySchedule() {
        val result = staged ?: return
        if (result.schedule.isEmpty()) return
        error = null
        result
            .withSelection(includeSchedule = true, includeGrades = false)
            .toProposal(snapshot)
            ?.let(currentOnReview)
    }

    fun applyGrades() {
        val result = staged ?: return
        if (result.grades.isEmpty()) return
        error = null
        result
            .withSelection(includeSchedule = false, includeGrades = true)
            .toProposal(snapshot)
            ?.let(currentOnReview)
    }

    fun forgetLogin() {
        runCatching { store.save(ErsCredentials(), remember = false) }
        studentId = ""
        password = ""
        birthday = ""
        rememberLogin = false
        error = null
        Toast.makeText(context, "Saved ERS login erased", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        delay(ERS_TIMEOUT_MS)
        if (running) {
            running = false
            status = ""
            error = "ERS took too long to respond. Try again in a moment."
            payloadSink.set(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            payloadSink.set(null)
            clearErsCookies()
        }
    }

    Column(Modifier.fillMaxSize()) {
        RibbonBar {
            if (tab == ErsSyncTab.CREDENTIALS) {
                RibbonButton(
                    icon = Icons.Outlined.CloudSync,
                    label = if (running) "Syncing" else "Sync ERS",
                    active = running,
                    onClick = { if (!running) start() },
                )
                RibbonSeparator()
                RibbonButton(
                    icon = Icons.Outlined.OpenInNew,
                    label = "Open ERS",
                    onClick = { openTupErs(context) },
                )
                RibbonButton(
                    icon = Icons.Outlined.DeleteSweep,
                    label = "Forget login",
                    onClick = { forgetLogin() },
                )
            }
            if (tab == ErsSyncTab.SCHEDULE) {
                RibbonButton(
                    icon = Icons.Outlined.GridView,
                    label = "Grid",
                    active = scheduleView == ScheduleViewMode.GRID,
                    onClick = {
                        scheduleView = ScheduleViewMode.GRID
                        viewPreferences.setScheduleView(ScheduleViewMode.GRID)
                    },
                )
                RibbonButton(
                    icon = Icons.Outlined.ViewList,
                    label = "List",
                    active = scheduleView == ScheduleViewMode.LIST,
                    onClick = {
                        scheduleView = ScheduleViewMode.LIST
                        viewPreferences.setScheduleView(ScheduleViewMode.LIST)
                    },
                )
                RibbonSeparator()
                RibbonButton(
                    icon = Icons.Outlined.Save,
                    label = "Apply schedule",
                    enabled = staged?.schedule?.isNotEmpty() == true && !running,
                    onClick = { applySchedule() },
                )
            }
            if (tab == ErsSyncTab.GRADES) {
                RibbonButton(
                    icon = Icons.Outlined.Save,
                    label = "Apply grades",
                    enabled = staged?.grades?.isNotEmpty() == true && !running,
                    onClick = { applyGrades() },
                )
            }
        }

        if (running) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaruAccent,
                    trackColor = MaruBorderSoft,
                )
                Text(
                    status.ifBlank { "Working…" },
                    color = MaruTextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        error?.let { message ->
            Text(
                message,
                color = MaruDanger,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ErsSyncTab.CREDENTIALS -> ErsCredentialsTab(
                    studentId = studentId,
                    onStudentIdChange = { studentId = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    birthday = birthday,
                    onBirthdayChange = { birthday = it },
                    rememberLogin = rememberLogin,
                    onRememberLoginChange = { rememberLogin = it },
                    syncedAt = stagedAt,
                    hasCached = staged != null,
                    onClearCached = {
                        cache.clear()
                        staged = null
                        stagedAt = 0L
                        Toast.makeText(context, "Saved ERS copy cleared", Toast.LENGTH_SHORT).show()
                    },
                    onSync = { start() },
                )

                ErsSyncTab.SCHEDULE -> ErsScheduleTab(
                    result = staged,
                    viewMode = scheduleView,
                    syncedAt = stagedAt,
                    onOpenCredentials = onOpenCredentials,
                )

                ErsSyncTab.GRADES -> ErsGradesTab(
                    result = staged,
                    syncedAt = stagedAt,
                    onOpenCredentials = onOpenCredentials,
                )
            }
        }

        if (running) {
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { viewContext ->
                    createErsWebView(
                        context = viewContext,
                        request = { request.get() },
                        bridge = bridge,
                        lastInjectedUrl = lastInjectedUrl,
                    )
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.removeJavascriptInterface("MaruErs")
                    webView.destroy()
                },
            )
        }
    }
}

@Composable
private fun BirthdayField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    fun openPicker() {
        val saved = ersBirthdayDate(value)
        val initial = Calendar.getInstance().apply {
            if (saved != null) {
                set(saved.year, saved.monthValue - 1, saved.dayOfMonth)
            } else {
                set(2000, Calendar.JANUARY, 1)
            }
        }
        DatePickerDialog(
            context,
            { _, year, month, day -> onValueChange(formatErsBirthday(year, month + 1, day)) },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    LaunchedEffect(focused) {
        if (focused) {
            focusManager.clearFocus()
            openPicker()
        }
    }

    MaruTextField(
        value = value,
        onValueChange = {},
        label = "Birthday",
        placeholder = "Tap to pick your birthday",
        readOnly = true,
        interactionSource = interactionSource,
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = "Pick birthday",
                tint = MaruTextFaint,
                modifier = Modifier.size(19.dp),
            )
        },
    )
}

internal enum class ErsSyncTab(val label: String) {
    CREDENTIALS("Credentials"),
    SCHEDULE("Schedule"),
    GRADES("Grades"),
}

@Composable
private fun ErsCredentialsTab(
    studentId: String,
    onStudentIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    birthday: String,
    onBirthdayChange: (String) -> Unit,
    rememberLogin: Boolean,
    onRememberLoginChange: (Boolean) -> Unit,
    syncedAt: Long,
    hasCached: Boolean,
    onClearCached: () -> Unit,
    onSync: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel("ERS login")
                MaruTextField(
                    value = studentId,
                    onValueChange = onStudentIdChange,
                    label = "Student ID",
                    placeholder = "e.g. TUPV-00-0000",
                    keyboardType = KeyboardType.Text,
                )
                MaruTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "ERS password",
                    placeholder = "Your ERS password",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                                tint = MaruTextFaint,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    },
                )
                BirthdayField(value = birthday, onValueChange = onBirthdayChange)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRememberLoginChange(!rememberLogin) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberLogin,
                        onCheckedChange = onRememberLoginChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaruAccent,
                            uncheckedColor = MaruBorderStrong,
                            checkmarkColor = Color.White,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Remember login on this phone",
                            color = MaruTextStrong,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Uncheck to erase the saved login after this sync.",
                            color = MaruTextFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
                MaruPrimaryButton(
                    text = "Sync from ERS",
                    icon = Icons.Outlined.CloudSync,
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (hasCached && syncedAt > 0L) {
            GlassCard(border = MaruAccentBorder) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionLabel("Saved copy", accent = MaruSuccess)
                    Text(
                        "From ${formatErsStamp(syncedAt)}. Your schedule and grades stay readable here even without a connection.",
                        color = MaruTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    MaruGhostButton(
                        text = "Clear saved copy",
                        onClick = onClearCached,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaruPanelDeep)
                .border(1.dp, MaruBorderSoft)
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "Stored on this phone only. Your password is encrypted, and Maru Study uses it only to sign in to ERS and import your schedule, subjects, and grades. Nothing is uploaded anywhere else.",
                color = MaruTextMuted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
            Text(
                "ERS sync depends on how the TUP Enrollment Registration System works when this app was last updated. If TUP changes the platform, this import may stop working.",
                color = MaruTextFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        Text(
            "After syncing, review Schedule and Grades, then apply each one from its toolbar. Nothing is replaced without your confirmation.",
            color = MaruTextFaint,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ErsScheduleTab(
    result: ErsImportResult?,
    viewMode: ScheduleViewMode,
    syncedAt: Long,
    onOpenCredentials: () -> Unit,
) {
    if (result == null || result.schedule.isEmpty()) {
        ErsEmptyState(
            title = "No schedule from ERS yet",
            message = "Sync from ERS in TUP Sync Credentials to preview your timetable here. Nothing is replaced until you tap Apply schedule.",
            actionLabel = "Open Credentials",
            onAction = onOpenCredentials,
        )
        return
    }

    val document = remember(result) { ScheduleDocument(entries = result.scheduleEntries()) }
    if (document.entries.isEmpty()) {
        ErsEmptyState(
            title = "No meeting times found",
            message = "ERS listed your subjects, but no class times came through. Open ERS to check your schedule block.",
            actionLabel = "Open Credentials",
            onAction = onOpenCredentials,
        )
        return
    }

    val header: @Composable () -> Unit = {
        ErsSyncHeader(
            term = result.displayTerm,
            summary = "${classCountLabel(document.entries.size)}  ·  ${result.schedule.size} subjects",
            syncedAt = syncedAt,
            action = "Preview only. Tap Apply schedule in the toolbar to save this timetable.",
        )
    }

    when (viewMode) {
        ScheduleViewMode.GRID -> GridView(
            document = document,
            onEdit = null,
            modifier = Modifier.fillMaxSize(),
            header = header,
        )

        ScheduleViewMode.LIST -> ListView(
            document = document,
            onEdit = null,
            modifier = Modifier.fillMaxSize(),
            header = header,
        )
    }
}

@Composable
private fun ErsGradesTab(
    result: ErsImportResult?,
    syncedAt: Long,
    onOpenCredentials: () -> Unit,
) {
    if (result == null || result.grades.isEmpty()) {
        ErsEmptyState(
            title = "No grades from ERS yet",
            message = "Sync from ERS in TUP Sync Credentials to see your grade timeline here. Nothing is replaced until you tap Apply grades.",
            actionLabel = "Open Credentials",
            onAction = onOpenCredentials,
        )
        return
    }

    val terms = if (result.gradeHistory.isNotEmpty()) {
        result.gradeHistory
    } else {
        listOf(ErsGradeTerm(term = result.gradesTerm, subjects = result.grades))
    }

    ErsGradesPager(terms = terms, syncedAt = syncedAt)
}

@Composable
private fun ErsGradesPager(
    terms: List<ErsGradeTerm>,
    syncedAt: Long,
) {
    val pagerState = rememberPagerState(pageCount = { terms.size })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage.coerceIn(0, terms.lastIndex)
    val term = terms[page]
    val gpa = term.gpa.trim().takeIf { it.isNotBlank() && it.toDoubleOrNull() != 0.0 }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                enabled = page > 0,
            ) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = "Newer term",
                    tint = if (page > 0) MaruTextMuted else MaruTextFaint.copy(alpha = 0.4f),
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        term.label,
                        color = MaruTextStrong,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (page == 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "LATEST",
                            color = MaruAccent,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier
                                .background(MaruAccentSoft, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append("${term.subjects.size} subjects")
                        if (gpa != null) append("  ·  GPA $gpa")
                        append("  ·  ${page + 1} / ${terms.size}")
                    },
                    color = MaruTextFaint,
                    fontSize = 10.5.sp,
                )
            }
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                enabled = page < terms.lastIndex,
            ) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Older term",
                    tint = if (page < terms.lastIndex) MaruTextMuted else MaruTextFaint.copy(alpha = 0.4f),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) { index ->
            val item = terms[index]
            val missing = item.subjects.count { it.prelim.isBlank() && it.midterm.isBlank() }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (index == 0 && missing > 0) {
                    Text(
                        "$missing subject${if (missing == 1) "" else "s"} still waiting on a prelim or midterm.",
                        color = MaruTextFaint,
                        fontSize = 11.sp,
                    )
                }
                item.subjects.forEach { subject -> ErsTimelineSubject(subject) }
                Spacer(Modifier.height(10.dp))
            }
        }

        Text(
            buildString {
                append("Swipe left or right to move through your terms.")
                val stamp = formatErsStamp(syncedAt)
                if (stamp.isNotBlank()) {
                    append("  Last synced ")
                    append(stamp)
                    append(".")
                }
            },
            color = MaruTextFaint,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ErsSyncHeader(
    term: String,
    summary: String,
    syncedAt: Long,
    action: String,
) {
    GlassCard(border = MaruAccentBorder) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SectionLabel("From TUP ERS", accent = MaruSuccess)
            if (term.isNotBlank()) {
                Text(
                    term,
                    color = MaruTextStrong,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(summary, color = MaruTextMuted, fontSize = 12.sp)
            val stamp = formatErsStamp(syncedAt)
            if (stamp.isNotBlank()) {
                Text("Last synced $stamp", color = MaruTextFaint, fontSize = 10.5.sp)
            }
            Text(action, color = MaruTextFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ErsEmptyState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = MaruTextStrong,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaruTextFaint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        MaruPrimaryButton(text = actionLabel, onClick = onAction)
    }
}

private fun formatErsStamp(millis: Long): String {
    if (millis <= 0L) return ""
    return runCatching {
        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(millis))
    }.getOrDefault("")
}

@Composable
private fun ErsTimelineSubject(subject: ErsSubjectRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaruPanelDeep)
            .border(1.dp, MaruBorderSoft)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    subject.code.ifBlank { subject.title.ifBlank { "Subject" } },
                    color = MaruTextStrong,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (subject.code.isNotBlank() && subject.title.isNotBlank()) {
                    Text(
                        buildString {
                            append(subject.title)
                            if (subject.units.isNotBlank()) {
                                append("  ·  ")
                                append(subject.units)
                                append(" units")
                            }
                        },
                        color = MaruTextMuted,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            val grade = subject.average.ifBlank { subject.endterm }
            Spacer(Modifier.width(8.dp))
            Text(
                grade.ifBlank { "--" },
                color = ersGradeColor(grade),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        val pills = buildList {
            if (subject.prelim.isNotBlank()) add("Prelim" to subject.prelim)
            if (subject.midterm.isNotBlank()) add("Midterm" to subject.midterm)
            if (subject.endterm.isNotBlank() && subject.endterm != subject.average) {
                add("Endterm" to subject.endterm)
            }
        }
        if (pills.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pills.forEach { (label, value) ->
                    ErsGradePill(label = label, value = value)
                }
            }
        }

        if (subject.status.isNotBlank()) {
            Text(
                subject.status,
                color = ersStatusColor(subject.status),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ErsGradePill(label: String, value: String) {
    Row(
        Modifier
            .background(MaruPanelSolid, CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label.uppercase(),
            color = MaruTextFaint,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Text(
            value,
            color = MaruTextStrong,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun ersGradeColor(value: String): Color {
    val grade = value.trim().toDoubleOrNull() ?: return MaruTextFaint
    return when {
        grade <= 3.0 -> MaruSuccess
        grade <= 4.0 -> Color(0xFFE8B465)
        else -> MaruDanger
    }
}

private fun ersStatusColor(status: String): Color {
    val value = status.trim().lowercase(Locale.US)
    return when {
        value.startsWith("fail") || value == "f" -> MaruDanger
        value.startsWith("pass") || value == "p" -> MaruSuccess
        value.contains("satisf") || value.contains("good") ||
            value.contains("superior") || value.contains("excellent") -> MaruSuccess
        else -> MaruTextFaint
    }
}

@SuppressLint("JavascriptInterface")
private fun createErsWebView(
    context: Context,
    request: () -> ErsRequest?,
    bridge: MaruErsBridge,
    lastInjectedUrl: AtomicReference<String?>,
): WebView {
    val webView = WebView(context)

    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        userAgentString = ersUserAgent(context)
    }

    webView.setBackgroundColor(AndroidColor.TRANSPARENT)
    webView.addJavascriptInterface(bridge, "MaruErs")

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, false)
    }

    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            if (url == null || !url.startsWith(ERS_ORIGIN)) return
            val current = request() ?: return
            if (lastInjectedUrl.getAndSet(url) == url) return
            view.evaluateJavascript(buildErsSyncScript(current), null)
        }
    }

    webView.loadUrl(ERS_LOGIN_URL)
    return webView
}

private class MaruErsBridge(
    private val sink: AtomicReference<((String) -> Unit)?>,
) {
    @JavascriptInterface
    fun post(payload: String) {
        val callback = sink.get() ?: return
        Handler(Looper.getMainLooper()).post { callback(payload) }
    }
}

private fun ersUserAgent(context: Context): String =
    WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(Regex("""Version/\d+(?:\.\d+)* """), "")

private fun clearErsCookies() {
    val manager = CookieManager.getInstance()
    val cookie = manager.getCookie(ERS_ORIGIN) ?: return

    cookie.split(";").forEach { part ->
        val name = part.substringBefore("=").trim()
        if (name.isEmpty()) return@forEach
        listOf("/", "/aims/", "/aims/students/", "/aims/process/").forEach { path ->
            manager.setCookie(ERS_ORIGIN, "$name=; Max-Age=0; Path=$path")
        }
    }
    manager.flush()
}

private fun jsQuote(value: String): String = JSONObject.quote(value)

private fun buildErsSyncScript(request: ErsRequest): String {
    val mode = jsQuote(request.mode.key)
    val user = jsQuote(request.credentials.studentId)
    val pass = jsQuote(request.credentials.password)
    val bdate = jsQuote(request.credentials.birthday)

    return """
(async () => {
  var MODE = $mode;
  var USER = $user;
  var PASS = $pass;
  var BDATE = $bdate;

  var norm = function (value) { return (value || "").replace(/\s+/g, " ").trim(); };
  var fail = function (code, message) { return { ok: false, error: code, message: message }; };
  var send = function (payload) { try { MaruErs.post(JSON.stringify(payload)); } catch (error) {} };

  var findTable = function (doc, required) {
    var tables = doc.querySelectorAll("table");
    for (var i = 0; i < tables.length; i++) {
      var table = tables[i];
      if (!table.rows.length) continue;
      var header = table.rows[0];
      if (header.cells.length < 6) continue;
      var names = [];
      for (var c = 0; c < header.cells.length; c++) names.push(norm(header.cells[c].innerText).toLowerCase());
      var matched = true;
      for (var r = 0; r < required.length; r++) {
        if (names.indexOf(required[r]) < 0) { matched = false; break; }
      }
      if (matched) return table;
    }
    return null;
  };

  var headersOf = function (table) {
    var names = [];
    var cells = table.rows[0].cells;
    for (var i = 0; i < cells.length; i++) names.push(norm(cells[i].innerText).toLowerCase());
    return names;
  };

  var column = function (headers, names) {
    for (var i = 0; i < names.length; i++) {
      var index = headers.indexOf(names[i]);
      if (index >= 0) return index;
    }
    return -1;
  };

  var cellText = function (row, index) {
    if (index < 0 || index >= row.cells.length) return "";
    return norm(row.cells[index].innerText);
  };

  var readPage = async function (path) {
    var response = await fetch(path, { credentials: "include" });
    var html = await response.text();
    if (/frmLogin/.test(html)) return null;
    return html;
  };

  try {
    var form = document.forms.frmLogin;
    if (form) {
      form.username.value = USER;
      form.password.value = PASS;
      form.bdate.value = BDATE;
      var body = new URLSearchParams();
      new FormData(form).forEach(function (value, key) { body.append(key, value); });
      var login = await fetch(form.action, { method: "POST", body: body, credentials: "include" });
      var loginHtml = await login.text();
      if (/frmLogin/.test(loginHtml)) {
        send(fail("credentials", "ERS rejected that ID, password, or birthday. Check them and try again."));
        return;
      }
    }

    var result = { ok: true, mode: MODE, scheduleTerm: "", gradesTerm: "", schedule: [], grades: [], gradeTerms: [] };

    if (MODE !== "grades") {
      var scheduleHtml = await readPage("/aims/students/schedule.php?mainID=105&menuDesc=Schedule");
      if (scheduleHtml === null) {
        send(fail("session", "The ERS session ended before the schedule loaded. Try again."));
        return;
      }
      var sdoc = new DOMParser().parseFromString(scheduleHtml, "text/html");
      var stable = findTable(sdoc, ["subject code", "schedule"]);
      if (!stable) {
        send(fail("no_schedule", "Could not find the schedule table on ERS. The site may have changed."));
        return;
      }
      var shead = headersOf(stable);
      var sCode = column(shead, ["subject code"]);
      var sDesc = column(shead, ["description"]);
      var sFac = column(shead, ["faculty", "faculty name"]);
      var sUnits = column(shead, ["units"]);
      var sSch = column(shead, ["schedule"]);

      for (var si = 1; si < stable.rows.length; si++) {
        var srow = stable.rows[si];
        if (srow.cells.length <= Math.max(sCode, sSch)) continue;
        if (!/^\d+$/.test(norm(srow.cells[0].innerText))) continue;
        result.schedule.push({
          code: cellText(srow, sCode),
          title: cellText(srow, sDesc),
          faculty: cellText(srow, sFac),
          units: cellText(srow, sUnits),
          section: "",
          schedule: cellText(srow, sSch)
        });
      }

      var sterm = norm(sdoc.body.innerText).match(/Term\/Sem:\s*([^\s]+)\s*SY:\s*(\d{4}\s*-\s*\d{4})/i);
      if (sterm) result.scheduleTerm = "SY " + sterm[2].replace(/\s+/g, "") + " · " + sterm[1];
    }

    if (MODE !== "schedule") {
      var gradesHtml = await readPage("/aims/students/grades.php?mainID=106&menuDesc=Grades");
      if (gradesHtml === null) {
        send(fail("session", "The ERS session ended before the grades loaded. Try again."));
        return;
      }
      var gdoc = new DOMParser().parseFromString(gradesHtml, "text/html");

      var termInfoFor = function (table) {
        var info = { schoolYear: "", term: "", gpa: "" };
        var cell = table.parentElement;
        var row = cell ? cell.parentElement : null;
        var body = row ? row.parentElement : null;
        var outer = body ? body.parentElement : null;
        if (!row || !outer || !outer.rows) return info;

        var limit = Math.min(row.rowIndex, outer.rows.length - 1);
        for (var i = 0; i <= limit; i++) {
          var text = norm(outer.rows[i].innerText);
          var match = text.match(/SCHOOL YEAR\s*(\d{4})\s*Term\s*([A-Za-z]+)/i);
          if (match) {
            info.schoolYear = match[1];
            info.term = match[2];
            info.gpa = "";
          }
          if (/GPA/i.test(text)) {
            var gpa = text.match(/GPA[^0-9]*([0-9]+(?:\.[0-9]+)?)/i);
            if (gpa) info.gpa = gpa[1];
          }
        }
        return info;
      };

      var gradeTables = [];
      var allGradeTables = gdoc.querySelectorAll("table");
      for (var gti = 0; gti < allGradeTables.length; gti++) {
        var candidate = allGradeTables[gti];
        if (!candidate.rows.length) continue;
        var candidateHeader = candidate.rows[0];
        if (candidateHeader.cells.length < 6) continue;
        var candidateNames = [];
        for (var hc = 0; hc < candidateHeader.cells.length; hc++) {
          candidateNames.push(norm(candidateHeader.cells[hc].innerText).toLowerCase());
        }
        if (candidateNames.indexOf("subject code") < 0) continue;
        if (candidateNames.indexOf("grade status") < 0 &&
            candidateNames.indexOf("average") < 0 &&
            candidateNames.indexOf("prelim") < 0) continue;
        gradeTables.push(candidate);
      }

      if (!gradeTables.length) {
        send(fail("no_grades", "Could not find the grades table on ERS. The site may have changed."));
        return;
      }

      for (var gti2 = 0; gti2 < gradeTables.length; gti2++) {
        var gtable = gradeTables[gti2];
        var ghead = headersOf(gtable);
        var gCode = column(ghead, ["subject code"]);
        var gDesc = column(ghead, ["description"]);
        var gFac = column(ghead, ["faculty name", "faculty"]);
        var gUnits = column(ghead, ["units"]);
        var gSection = column(ghead, ["section"]);
        var gPrelim = column(ghead, ["prelim", "prelim grade"]);
        var gMid = column(ghead, ["midterm", "midterm grade"]);
        var gEnd = column(ghead, ["endterm", "end-term", "end term", "endterm grade", "end term grade"]);
        var gAvg = column(ghead, ["average"]);
        var gFinal = column(ghead, ["final grade", "final"]);
        var gStatus = column(ghead, ["grade status"]);

        var subjects = [];
        for (var gi = 1; gi < gtable.rows.length; gi++) {
          var grow = gtable.rows[gi];
          if (grow.cells.length <= Math.max(gCode, gAvg, gFinal)) continue;
          if (!/^\d+$/.test(norm(grow.cells[0].innerText))) continue;
          var average = cellText(grow, gAvg);
          if (!average) average = cellText(grow, gFinal);
          subjects.push({
            code: cellText(grow, gCode),
            title: cellText(grow, gDesc),
            faculty: cellText(grow, gFac),
            units: cellText(grow, gUnits),
            section: cellText(grow, gSection),
            schedule: "",
            prelim: cellText(grow, gPrelim),
            midterm: cellText(grow, gMid),
            endterm: cellText(grow, gEnd),
            average: average,
            status: cellText(grow, gStatus)
          });
        }

        if (!subjects.length) continue;
        var info = termInfoFor(gtable);
        result.gradeTerms.push({
          schoolYear: info.schoolYear,
          term: info.term,
          gpa: info.gpa,
          subjects: subjects
        });
      }

      if (!result.gradeTerms.length) {
        send(fail("no_grades", "Could not read any grades from ERS. The site may have changed."));
        return;
      }

      var latest = result.gradeTerms[0];
      result.grades = latest.subjects;
      result.gradesTerm = (latest.schoolYear ? "SY " + latest.schoolYear + " · " : "") + latest.term;
    }

    send(result);
  } catch (error) {
    send(fail("network", "Could not reach ERS. Check your connection and try again."));
  }
})();
""".trimIndent()
}
