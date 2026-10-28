package com.maru.study

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

internal enum class ScheduleViewMode(val label: String) {
    GRID("Grid"),
    LIST("List"),
}

@Composable
fun ScheduleScreen(
    document: ScheduleDocument,
    onDocumentChange: (ScheduleDocument) -> Unit,
    newClassSignal: Int = 0,
) {
    val context = LocalContext.current
    val reminderStore = remember { ReminderStore(context) }
    val viewPreferences = remember { ViewPreferences(context) }
    var viewMode by rememberSaveable { mutableStateOf(viewPreferences.scheduleView()) }
    var editorEntry by remember { mutableStateOf<ScheduleEntry?>(null) }
    var editorIsNew by remember { mutableStateOf(true) }
    var notificationsVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduleEntry?>(null) }

    fun openNew() {
        editorEntry = ScheduleEntry(
            id = "entry-${System.currentTimeMillis()}",
            day = StudyDay.fromDate(LocalDate.now()) ?: StudyDay.MONDAY,
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 30,
            accent = EntryAccent.SKY,
        )
        editorIsNew = true
    }

    fun openEdit(entry: ScheduleEntry) {
        editorEntry = entry
        editorIsNew = false
    }

    LaunchedEffect(newClassSignal) {
        if (newClassSignal > 0) openNew()
    }

    fun chooseView(mode: ScheduleViewMode) {
        viewMode = mode
        viewPreferences.setScheduleView(mode)
    }

    fun upsert(entry: ScheduleEntry) {
        val exists = document.entries.any { it.id == entry.id }
        val next = if (exists) {
            document.entries.map { if (it.id == entry.id) entry else it }
        } else {
            document.entries + entry
        }
        onDocumentChange(
            document.copy(
                entries = next.sortedWith(
                    compareBy({ it.day.ordinal }, { it.startMinute }, { it.endMinute })
                )
            )
        )
    }

    fun delete(entry: ScheduleEntry) {
        onDocumentChange(
            document.copy(entries = document.entries.filterNot { it.id == entry.id })
        )
    }

    Column(Modifier.fillMaxSize()) {
        RibbonBar {
            RibbonButton(
                icon = Icons.Outlined.GridView,
                label = "Grid",
                active = viewMode == ScheduleViewMode.GRID,
                onClick = { chooseView(ScheduleViewMode.GRID) },
            )
            RibbonButton(
                icon = Icons.Outlined.ViewList,
                label = "List",
                active = viewMode == ScheduleViewMode.LIST,
                onClick = { chooseView(ScheduleViewMode.LIST) },
            )
            RibbonSeparator()
            RibbonButton(
                icon = Icons.Filled.Add,
                label = "Add class",
                onClick = { openNew() },
            )
            RibbonSeparator()
            RibbonButton(
                icon = Icons.Outlined.Notifications,
                label = "Reminders",
                onClick = { notificationsVisible = true },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (viewMode) {
                ScheduleViewMode.GRID -> GridView(
                    document = document,
                    onEdit = ::openEdit,
                    modifier = Modifier.fillMaxSize(),
                )

                ScheduleViewMode.LIST -> ListView(
                    document = document,
                    onEdit = ::openEdit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    editorEntry?.let { entry ->
        ScheduleEditorDialog(
            initial = entry,
            isNew = editorIsNew,
            onDismiss = { editorEntry = null },
            onSave = { updated ->
                upsert(updated)
                editorEntry = null
            },
            onDelete = {
                editorEntry = null
                deleteTarget = entry
            },
        )
    }

    if (notificationsVisible) {
        NotificationSettingsDialog(
            settings = reminderStore.load(),
            onDismiss = { notificationsVisible = false },
            onSave = { settings ->
                reminderStore.save(settings)
                MaruReminders.scheduleClasses(context, document, settings)
                notificationsVisible = false
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaruPanelSolid,
            shape = MaruShape,
            title = { Text("Delete class?", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("${entry.displayTitle} will be removed.", color = MaruTextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    delete(entry)
                    deleteTarget = null
                }) { Text("Delete", color = MaruDanger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = MaruTextMuted) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Grid
// ---------------------------------------------------------------------------

@Composable
internal fun GridView(
    document: ScheduleDocument,
    onEdit: ((ScheduleEntry) -> Unit)?,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val days = document.activeDays()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (header != null) {
            item(key = "grid-header-slot") { header() }
        }
        if (days.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "Your timetable is empty",
                    subtitle = "Use Add class in the toolbar to start.",
                )
            }
        }
        days.forEach { day ->
            val entries = document.entriesFor(day)
            item(key = "grid-header-${day.name}") {
                Box(Modifier.animateItem()) {
                    DayHeader(day, entries)
                }
            }
            entries.chunked(2).forEachIndexed { chunkIndex, chunk ->
                item(key = "grid-${day.name}-$chunkIndex") {
                    Row(
                        Modifier.animateItem(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        chunk.forEach { entry ->
                            GridCard(entry, Modifier.weight(1f), onEdit)
                        }
                        if (chunk.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: StudyDay, entries: List<ScheduleEntry>) {
    val minutes = entries.sumOf { it.durationMinutes }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.label.uppercase(),
            color = MaruTextStrong,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${classCountLabel(entries.size)}  ·  ${formatDuration(minutes)}",
            color = MaruTextFaint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GridCard(
    entry: ScheduleEntry,
    modifier: Modifier = Modifier,
    onEdit: ((ScheduleEntry) -> Unit)?,
) {
    GlassCard(modifier, onClick = onEdit?.let { edit -> { edit(entry) } }) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(entry.accent.color))
        Column(
            Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                formatMinute(entry.startMinute),
                color = entry.accent.color,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
            )
            Text(
                entry.displayTitle,
                color = MaruTextStrong,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                minLines = 2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                entry.room.takeIf { it.isNotBlank() },
                entry.instructor.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(meta, color = MaruTextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@Composable
internal fun ListView(
    document: ScheduleDocument,
    onEdit: ((ScheduleEntry) -> Unit)?,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val days = document.activeDays()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item(key = "list-header-slot") { header() }
        }
        if (days.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "Your timetable is empty",
                    subtitle = "Use Add class in the toolbar to start.",
                )
            }
        }
        days.forEach { day ->
            val entries = document.entriesFor(day)
            item(key = "list-header-${day.name}") {
                Box(Modifier.animateItem()) {
                    DayHeader(day, entries)
                }
            }
            items(entries, key = { "list-${it.id}" }) { entry ->
                Box(Modifier.animateItem()) {
                    ListRow(entry, onClick = onEdit?.let { edit -> { edit(entry) } })
                }
            }
        }
    }
}

@Composable
private fun ListRow(entry: ScheduleEntry, onClick: (() -> Unit)? = null) {
    GlassCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(70.dp)) {
                Text(formatMinute(entry.startMinute), color = MaruTextStrong, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Text(formatMinute(entry.endMinute), color = MaruTextFaint, fontSize = 11.sp)
            }
            Box(Modifier.width(2.dp).height(38.dp).background(entry.accent.color))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayTitle,
                    color = MaruTextStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    entry.courseCode.takeIf { it.isNotBlank() && it != entry.displayTitle },
                    entry.room.takeIf { it.isNotBlank() },
                    entry.section.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(meta, color = MaruTextFaint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Entry editor
// ---------------------------------------------------------------------------

@Composable
private fun ScheduleEditorDialog(
    initial: ScheduleEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var timePickerTarget by remember { mutableStateOf<TimeField?>(null) }

    val canSave = (draft.title.isNotBlank() || draft.courseCode.isNotBlank()) && draft.endMinute > draft.startMinute

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = MaruShape,
            color = MaruPanelDeepSolid,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaruBorderSoft),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaruPanelSolid)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isNew) "NEW CLASS" else "EDIT CLASS",
                            color = MaruAccent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            draft.displayTitle,
                            color = MaruTextStrong,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaruTextMuted)
                    }
                }
                MaruDivider()

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MaruTextField(
                            value = draft.courseCode,
                            onValueChange = { draft = draft.copy(courseCode = it) },
                            label = "Course code",
                            placeholder = "e.g. MATH 101",
                            modifier = Modifier.weight(1f),
                        )
                        MaruTextField(
                            value = draft.section,
                            onValueChange = { draft = draft.copy(section = it) },
                            label = "Section",
                            placeholder = "e.g. A",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    MaruTextField(
                        value = draft.title,
                        onValueChange = { draft = draft.copy(title = it) },
                        label = "Subject title",
                        placeholder = "e.g. Calculus 1",
                    )

                    SectionLabel("Day")
                    StudyDay.entries.chunked(3).forEach { rowDays ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowDays.forEach { day ->
                                val active = draft.day == day
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .background(if (active) MaruAccentSoft else MaruPanel)
                                        .border(1.dp, if (active) MaruAccentBorder else MaruBorderSoft)
                                        .clickable { draft = draft.copy(day = day) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        day.short,
                                        color = if (active) MaruTextStrong else MaruTextMuted,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp,
                                    )
                                }
                            }
                        }
                    }

                    SectionLabel("Time")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        TimeFieldButton(
                            label = "Start",
                            minute = draft.startMinute,
                            modifier = Modifier.weight(1f),
                            onClick = { timePickerTarget = TimeField.START },
                        )
                        TimeFieldButton(
                            label = "End",
                            minute = draft.endMinute,
                            modifier = Modifier.weight(1f),
                            onClick = { timePickerTarget = TimeField.END },
                        )
                    }
                    if (draft.endMinute <= draft.startMinute) {
                        Text(
                            "End time must be after the start time.",
                            color = MaruDanger,
                            fontSize = 10.5.sp,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MaruTextField(
                            value = draft.instructor,
                            onValueChange = { draft = draft.copy(instructor = it) },
                            label = "Instructor",
                            placeholder = "e.g. Dela Cruz",
                            modifier = Modifier.weight(1f),
                        )
                        MaruTextField(
                            value = draft.room,
                            onValueChange = { draft = draft.copy(room = it) },
                            label = "Room",
                            placeholder = "e.g. Room 204",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    MaruTextField(
                        value = draft.notes,
                        onValueChange = { draft = draft.copy(notes = it) },
                        label = "Notes",
                        placeholder = "Bring calculator",
                        singleLine = false,
                        minLines = 2,
                    )

                    SectionLabel("Accent")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryAccent.entries.forEach { accent ->
                            val active = draft.accent == accent
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(accent.color.copy(alpha = if (active) 0.95f else 0.28f))
                                    .border(
                                        if (active) 2.dp else 1.dp,
                                        if (active) MaruTextStrong else MaruBorderSoft,
                                    )
                                    .clickable { draft = draft.copy(accent = accent) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (active) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaruPageBottom,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                MaruDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaruPanelSolid)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isNew) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .border(1.dp, MaruDanger.copy(alpha = 0.5f))
                                .clickable { onDelete() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete class", tint = MaruDanger, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MaruGhostButton(text = "Cancel", onClick = onDismiss)
                    MaruPrimaryButton(
                        text = if (isNew) "Add" else "Save",
                        onClick = { onSave(draft) },
                        enabled = canSave,
                    )
                }
            }
        }
    }

    when (timePickerTarget) {
        TimeField.START -> TimePickerDialog(
            title = "Start time",
            initialMinute = draft.startMinute,
            onDismiss = { timePickerTarget = null },
            onConfirm = { minute ->
                val safeEnd = if (draft.endMinute <= minute) minute + 60 else draft.endMinute
                draft = draft.copy(startMinute = minute, endMinute = safeEnd)
                timePickerTarget = null
            },
        )

        TimeField.END -> TimePickerDialog(
            title = "End time",
            initialMinute = draft.endMinute,
            onDismiss = { timePickerTarget = null },
            onConfirm = { minute ->
                draft = draft.copy(endMinute = minute)
                timePickerTarget = null
            },
        )

        null -> Unit
    }
}

private enum class TimeField { START, END }

@Composable
private fun TimeFieldButton(
    label: String,
    minute: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .background(MaruPanel)
            .border(1.dp, MaruBorderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label.uppercase(),
            color = MaruTextFaint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
        )
        Text(formatMinute(minute), color = MaruTextStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaruPanelSolid,
        shape = MaruShape,
        title = { Text(title, color = MaruTextStrong, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text("Set", color = MaruAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaruTextMuted) }
        },
    )
}

// ---------------------------------------------------------------------------
// Reminder settings
// ---------------------------------------------------------------------------

@Composable
private fun NotificationSettingsDialog(
    settings: ReminderSettings,
    onDismiss: () -> Unit,
    onSave: (ReminderSettings) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.enabled) }
    var lead by remember { mutableStateOf(settings.leadMinutes) }
    val allowed = MaruReminders.canNotify(context)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth(0.92f),
            shape = MaruShape,
            color = MaruPanelDeepSolid,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaruBorderSoft),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(MaruPanelSolid).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "CLASS REMINDERS",
                        color = MaruAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaruTextMuted)
                    }
                }
                MaruDivider()
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Remind me before class",
                            color = MaruTextStrong,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaruAccentDeep,
                                uncheckedThumbColor = MaruTextFaint,
                                uncheckedTrackColor = MaruPanelDeep,
                            ),
                        )
                    }

                    if (enabled) {
                        SectionLabel("Lead time")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15, 30, 60).forEach { minutes ->
                                val active = lead == minutes
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .background(if (active) MaruAccentSoft else MaruPanel)
                                        .border(1.dp, if (active) MaruAccentBorder else MaruBorderSoft)
                                        .clickable { lead = minutes }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (minutes < 60) "${minutes}m" else "1h",
                                        color = if (active) MaruTextStrong else MaruTextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    if (!allowed) {
                        MaruGhostButton(
                            text = "Open notification settings",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                MaruDivider()
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    MaruGhostButton("Cancel", onDismiss)
                    MaruPrimaryButton("Save", { onSave(ReminderSettings(enabled, lead)) })
                }
            }
        }
    }
}
