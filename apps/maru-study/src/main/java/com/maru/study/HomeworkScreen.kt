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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeworkScreen(
    tasks: List<HomeworkTask>,
    subjects: List<String>,
    onTasksChange: (List<HomeworkTask>) -> Unit,
    onAddSubject: () -> Unit,
) {
    var editor by remember { mutableStateOf<HomeworkTask?>(null) }
    var editorIsNew by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<HomeworkTask?>(null) }

    val pending = tasks
        .filterNot { it.done }
        .sortedWith(compareBy({ it.dueAtMillis ?: Long.MAX_VALUE }, { it.title.lowercase() }))
    val done = tasks.filter { it.done }.sortedByDescending { it.dueAtMillis ?: 0L }

    fun upsert(task: HomeworkTask) {
        val next = if (tasks.any { it.id == task.id }) {
            tasks.map { if (it.id == task.id) task else it }
        } else {
            tasks + task
        }
        onTasksChange(next)
    }

    fun replace(task: HomeworkTask) = onTasksChange(tasks.map { if (it.id == task.id) task else it })

    Column(Modifier.fillMaxSize()) {
        RibbonBar {
            RibbonButton(
                icon = Icons.Filled.Add,
                label = "Add homework",
                onClick = {
                    editor = HomeworkTask(id = "task-${System.currentTimeMillis()}")
                    editorIsNew = true
                },
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (tasks.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "No homework yet",
                        subtitle = "Tap + to add a deadline.",
                    )
                }
            }

            if (pending.isNotEmpty()) {
                item {
                    SectionLabel(
                        title = "Pending",
                        trailing = { Text(pending.size.toString(), color = MaruTextFaint, fontSize = 10.sp) },
                    )
                }
                items(pending, key = { "pending-${it.id}" }) { task ->
                    Box(Modifier.animateItem()) {
                        HomeworkCard(
                            task = task,
                            onToggle = { replace(task.copy(done = !task.done)) },
                            onClick = {
                                editor = task
                                editorIsNew = false
                            },
                        )
                    }
                }
            }

            if (done.isNotEmpty()) {
                item {
                    SectionLabel(
                        title = "Done",
                        accent = MaruTextFaint,
                        trailing = { Text(done.size.toString(), color = MaruTextFaint, fontSize = 10.sp) },
                    )
                }
                items(done, key = { "done-${it.id}" }) { task ->
                    Box(Modifier.animateItem()) {
                        HomeworkCard(
                            task = task,
                            onToggle = { replace(task.copy(done = !task.done)) },
                            onClick = {
                                editor = task
                                editorIsNew = false
                            },
                        )
                    }
                }
            }
        }
    }

    editor?.let { task ->
        HomeworkEditorDialog(
            initial = task,
            isNew = editorIsNew,
            subjects = subjects,
            onAddSubject = onAddSubject,
            onDismiss = { editor = null },
            onSave = {
                upsert(it)
                editor = null
            },
            onDelete = {
                editor = null
                deleteTarget = task
            },
        )
    }

    deleteTarget?.let { task ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaruPanelSolid,
            shape = MaruShape,
            title = { Text("Delete homework?", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text(task.title.ifBlank { "This task" }, color = MaruTextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onTasksChange(tasks.filterNot { it.id == task.id })
                    deleteTarget = null
                }) { Text("Delete", color = MaruDanger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = MaruTextMuted) }
            },
        )
    }
}

@Composable
private fun HomeworkCard(
    task: HomeworkTask,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val overdue = !task.done && task.dueAtMillis != null && task.dueAtMillis < System.currentTimeMillis()
    val statusColor = when {
        task.done -> MaruTextFaint
        overdue -> MaruDanger
        else -> MaruTextMuted
    }

    GlassCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(if (task.done) MaruAccentDeep else Color.Transparent)
                    .border(1.dp, if (task.done) MaruAccent else MaruBorderStrong)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (task.done) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title.ifBlank { "Untitled task" },
                    color = if (task.done) MaruTextFaint else MaruTextStrong,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
                val meta = listOfNotNull(
                    task.subject.takeIf { it.isNotBlank() },
                    homeworkStatus(task).takeIf { !task.done },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(meta, color = statusColor, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (task.reminder && !task.done) {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = "Reminder set",
                    tint = MaruTextFaint,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeworkEditorDialog(
    initial: HomeworkTask,
    isNew: Boolean,
    subjects: List<String>,
    onAddSubject: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (HomeworkTask) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var subject by remember(initial.id) { mutableStateOf(initial.subject) }
    var notes by remember(initial.id) { mutableStateOf(initial.notes) }
    var reminder by remember(initial.id) { mutableStateOf(initial.reminder) }

    val initialDue = initial.dueAtMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
    var dueDate by remember(initial.id) {
        mutableStateOf(initialDue?.toLocalDate() ?: LocalDate.now())
    }
    var dueTime by remember(initial.id) {
        mutableStateOf(initialDue?.toLocalTime() ?: LocalTime.of(18, 0))
    }
    var datePickerVisible by remember { mutableStateOf(false) }
    var timePickerVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxSize(0.9f),
            shape = MaruShape,
            color = MaruPanelDeepSolid,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaruBorderSoft),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(MaruPanelSolid).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isNew) "NEW HOMEWORK" else "EDIT HOMEWORK",
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
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MaruTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = "Task",
                        placeholder = "Problem set 4",
                    )
                    SubjectPicker(
                        value = subject,
                        subjects = subjects,
                        onSelect = { subject = it },
                        onAddSubject = onAddSubject,
                    )

                    SectionLabel("Deadline")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        DueField(
                            label = "Date",
                            value = dueDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)),
                            modifier = Modifier.weight(1f),
                            onClick = { datePickerVisible = true },
                        )
                        DueField(
                            label = "Time",
                            value = dueTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
                            modifier = Modifier.weight(1f),
                            onClick = { timePickerVisible = true },
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Remind me 30 min before",
                            color = MaruTextStrong,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = reminder,
                            onCheckedChange = { reminder = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaruAccentDeep,
                                uncheckedThumbColor = MaruTextFaint,
                                uncheckedTrackColor = MaruPanelDeep,
                            ),
                        )
                    }

                    MaruTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes",
                        placeholder = "Optional",
                        singleLine = false,
                        minLines = 2,
                    )
                }

                MaruDivider()
                Row(
                    Modifier.fillMaxWidth().background(MaruPanelSolid).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isNew) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .border(1.dp, MaruDanger.copy(alpha = 0.5f))
                                .clickable(onClick = onDelete),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaruDanger, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MaruGhostButton("Cancel", onDismiss)
                    MaruPrimaryButton(
                        text = if (isNew) "Add" else "Save",
                        onClick = {
                            onSave(
                                initial.copy(
                                    title = title.trim(),
                                    subject = subject.trim(),
                                    dueAtMillis = dueDate
                                        .atTime(dueTime)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli(),
                                    reminder = reminder,
                                    notes = notes.trim(),
                                )
                            )
                        },
                        enabled = title.isNotBlank(),
                    )
                }
            }
        }
    }

    if (datePickerVisible) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { datePickerVisible = false },
            shape = MaruShape,
            colors = DatePickerDefaults.colors(containerColor = MaruPanelSolid),
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dueDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                    }
                    datePickerVisible = false
                }) { Text("Set", color = MaruAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerVisible = false }) {
                    Text("Cancel", color = MaruTextMuted)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (timePickerVisible) {
        val state = rememberTimePickerState(
            initialHour = dueTime.hour,
            initialMinute = dueTime.minute,
            is24Hour = false,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { timePickerVisible = false },
            containerColor = MaruPanelSolid,
            shape = MaruShape,
            title = { Text("Deadline time", color = MaruTextStrong, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    dueTime = LocalTime.of(state.hour, state.minute)
                    timePickerVisible = false
                }) { Text("Set", color = MaruAccent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { timePickerVisible = false }) {
                    Text("Cancel", color = MaruTextMuted)
                }
            },
        )
    }
}

@Composable
private fun SubjectPicker(
    value: String,
    subjects: List<String>,
    onSelect: (String) -> Unit,
    onAddSubject: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "SUBJECT",
            color = MaruTextFaint,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaruPanelDeep)
                .border(1.dp, MaruBorderSoft)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value.ifBlank { "Select a subject" },
                color = if (value.isBlank()) MaruTextFaint else MaruTextStrong,
                fontSize = 15.sp,
                fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaruTextFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (open) {
        Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                Modifier.fillMaxWidth(0.9f),
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
                            "SUBJECT",
                            color = MaruAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { open = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaruTextMuted)
                        }
                    }
                    MaruDivider()

                    if (subjects.isEmpty()) {
                        Text(
                            "No subjects yet. Add a class in Schedule first.",
                            color = MaruTextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 330.dp)) {
                            items(subjects, key = { it }) { subject ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelect(subject)
                                            open = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        subject,
                                        color = if (subject == value) MaruAccent else MaruTextStrong,
                                        fontSize = 14.5.sp,
                                        fontWeight = if (subject == value) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (subject == value) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaruAccent,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                                MaruDivider(color = MaruBorderSoft.copy(alpha = 0.4f))
                            }
                        }
                    }

                    MaruDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        MaruGhostButton(
                            text = "Add subject in Schedule",
                            icon = Icons.Filled.Add,
                            onClick = {
                                open = false
                                onAddSubject()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DueField(
    label: String,
    value: String,
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
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
        )
        Text(value, color = MaruTextStrong, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
