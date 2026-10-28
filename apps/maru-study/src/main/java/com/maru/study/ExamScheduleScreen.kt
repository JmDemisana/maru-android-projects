package com.maru.study

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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ExamScheduleScreen(
    exams: List<ExamEntry>,
    onExamsChange: (List<ExamEntry>) -> Unit,
) {
    var editorExam by remember { mutableStateOf<ExamEntry?>(null) }
    var editorIsNew by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<ExamEntry?>(null) }

    fun openNew() {
        editorExam = ExamEntry(
            id = "exam-${System.currentTimeMillis()}",
            date = LocalDate.now().toString(),
        )
        editorIsNew = true
    }

    fun upsert(exam: ExamEntry) {
        val exists = exams.any { it.id == exam.id }
        val next = if (exists) {
            exams.map { if (it.id == exam.id) exam else it }
        } else {
            exams + exam
        }
        onExamsChange(next.sortedExams())
    }

    fun delete(exam: ExamEntry) {
        onExamsChange(exams.filterNot { it.id == exam.id })
    }

    Column(Modifier.fillMaxSize()) {
        RibbonBar {
            RibbonButton(
                icon = Icons.Filled.Add,
                label = "Add exam",
                onClick = { openNew() },
            )
            RibbonSeparator()
            Text(
                if (exams.isEmpty()) "No exams saved" else examCountLabel(exams.size),
                color = MaruTextFaint,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }

        ExamList(
            exams = exams,
            onEdit = { exam ->
                editorExam = exam
                editorIsNew = false
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    editorExam?.let { exam ->
        ExamEditorDialog(
            initial = exam,
            isNew = editorIsNew,
            onDismiss = { editorExam = null },
            onSave = {
                upsert(it)
                editorExam = null
            },
            onDelete = {
                editorExam = null
                deleteTarget = exam
            },
        )
    }

    deleteTarget?.let { exam ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaruPanelSolid,
            shape = MaruShape,
            title = {
                Text(
                    "Delete exam?",
                    color = MaruTextStrong,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Text(
                    "${exam.title.ifBlank { "This exam" }} will be removed.",
                    color = MaruTextMuted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    delete(exam)
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaruDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = MaruTextMuted)
                }
            },
        )
    }
}

@Composable
private fun ExamList(
    exams: List<ExamEntry>,
    onEdit: (ExamEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = exams.sortedExams()
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (sorted.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No exams scheduled",
                    subtitle = "Use Add exam in the toolbar, or ask Nami to read an exam schedule for you.",
                    accent = MaruAccent,
                )
            }
        }

        var lastMonth = ""
        sorted.forEach { exam ->
            val month = examMonthKey(exam.date)
            if (month != lastMonth) {
                lastMonth = month
                item(key = "exam-month-$month") {
                    Text(
                        month.uppercase(Locale.US),
                        color = MaruTextStrong,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item(key = "exam-${exam.id}") {
                ExamRow(exam, onClick = { onEdit(exam) })
            }
        }
    }
}

@Composable
private fun ExamRow(exam: ExamEntry, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.width(52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    examMonthShort(exam.date),
                    color = MaruAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Text(
                    examDayNumber(exam.date),
                    color = MaruTextStrong,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.width(2.dp).height(42.dp).background(MaruAccent.copy(alpha = 0.7f)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    exam.title.ifBlank { "Exam" },
                    color = MaruTextStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    examTimeRange(exam.start, exam.end).takeIf { it.isNotBlank() },
                    exam.room.takeIf { it.isNotBlank() },
                    examDateLabel(exam.date).takeIf { exam.date.isBlank() },
                ).joinToString("  ·  ")
                Text(
                    meta.ifBlank { "Time and room not set" },
                    color = MaruTextFaint,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (exam.notes.isNotBlank()) {
                    Text(
                        exam.notes,
                        color = MaruTextMuted,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Exam editor
// ---------------------------------------------------------------------------

@Composable
private fun ExamEditorDialog(
    initial: ExamEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExamEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var timeTarget by remember { mutableStateOf<ExamTimeField?>(null) }
    var datePickerVisible by remember { mutableStateOf(false) }

    val canSave = draft.title.isNotBlank()

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
                            if (isNew) "NEW EXAM" else "EDIT EXAM",
                            color = MaruAccent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            draft.title.ifBlank { "Untitled exam" },
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
                    MaruTextField(
                        value = draft.title,
                        onValueChange = { draft = draft.copy(title = it) },
                        label = "Exam",
                        placeholder = "e.g. MATH 101 Final",
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MaruTextField(
                            value = draft.date,
                            onValueChange = { draft = draft.copy(date = it) },
                            label = "Date",
                            placeholder = "yyyy-MM-dd",
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .height(56.dp)
                                .background(MaruPanel)
                                .border(1.dp, MaruBorderSoft)
                                .clickable { datePickerVisible = true }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DateRange,
                                contentDescription = "Pick date",
                                tint = MaruTextMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (draft.date.isNotBlank() && parseExamDate(draft.date) == null) {
                        Text(
                            "Use the yyyy-MM-dd format, like 2026-11-12.",
                            color = MaruDanger,
                            fontSize = 10.5.sp,
                        )
                    }

                    SectionLabel("Time")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ExamTimeFieldButton(
                            label = "Start",
                            raw = draft.start,
                            modifier = Modifier.weight(1f),
                            onClick = { timeTarget = ExamTimeField.START },
                        )
                        ExamTimeFieldButton(
                            label = "End",
                            raw = draft.end,
                            modifier = Modifier.weight(1f),
                            onClick = { timeTarget = ExamTimeField.END },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
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
                        placeholder = "Coverage, permit, what to bring",
                        singleLine = false,
                        minLines = 2,
                    )
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
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete exam",
                                tint = MaruDanger,
                                modifier = Modifier.size(18.dp),
                            )
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

    when (timeTarget) {
        ExamTimeField.START -> ExamTimePickerDialog(
            title = "Start time",
            initialMinute = parseExamMinute(draft.start) ?: (9 * 60),
            onDismiss = { timeTarget = null },
            onConfirm = { minute ->
                draft = draft.copy(start = minuteToRaw(minute))
                timeTarget = null
            },
        )

        ExamTimeField.END -> ExamTimePickerDialog(
            title = "End time",
            initialMinute = parseExamMinute(draft.end) ?: (11 * 60),
            onDismiss = { timeTarget = null },
            onConfirm = { minute ->
                draft = draft.copy(end = minuteToRaw(minute))
                timeTarget = null
            },
        )

        null -> Unit
    }

    if (datePickerVisible) {
        ExamDatePickerDialog(
            initial = parseExamDate(draft.date),
            onDismiss = { datePickerVisible = false },
            onConfirm = { date ->
                draft = draft.copy(date = date.toString())
                datePickerVisible = false
            },
        )
    }
}

private enum class ExamTimeField { START, END }

private fun minuteToRaw(minute: Int): String =
    "%02d:%02d".format(Locale.US, minute / 60, minute % 60)

@Composable
private fun ExamTimeFieldButton(
    label: String,
    raw: String,
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
        Text(
            if (raw.isBlank()) "Set" else examTimeLabel(raw),
            color = if (raw.isBlank()) MaruTextFaint else MaruTextStrong,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamTimePickerDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamDatePickerDialog(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onConfirm(
                            Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate(),
                        )
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text("Set", color = MaruAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaruTextMuted) }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = MaruPanelSolid,
        ),
    ) {
        DatePicker(state = state)
    }
}

// ---------------------------------------------------------------------------
// Date labels
// ---------------------------------------------------------------------------

private fun examMonthKey(raw: String): String =
    parseExamDate(raw)?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
        ?: "No date"

private fun examMonthShort(raw: String): String =
    parseExamDate(raw)?.format(DateTimeFormatter.ofPattern("MMM", Locale.US))
        ?.uppercase(Locale.US)
        ?: "TBD"

private fun examDayNumber(raw: String): String =
    parseExamDate(raw)?.dayOfMonth?.toString() ?: "--"
