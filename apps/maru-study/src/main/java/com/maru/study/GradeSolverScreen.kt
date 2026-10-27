package com.maru.study

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BulkSubject(
    val id: Int,
    val course: String = "",
    val prelim: String = "",
    val midterm: String = "",
)

@Composable
fun GradeSolverScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var bulkMode by remember { mutableStateOf(false) }
    var prelim by remember { mutableStateOf("") }
    var midterm by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    val bulkSubjects = remember { mutableStateListOf(BulkSubject(id = 1)) }
    var nextBulkId by remember { mutableStateOf(2) }

    val required = computeRequiredEndterm(prelim, midterm)

    fun copyGrade() {
        val text = formatRequiredEndterm(required)
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    fun exportPng() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                if (bulkMode) {
                    GradeReportExport.saveBulk(
                        context,
                        bulkSubjects.map { Triple(it.course, it.prelim, it.midterm) },
                    )
                } else {
                    GradeReportExport.saveSingle(context, course, prelim, midterm)
                }
            }
            val message = if (path != null) "Saved to Pictures/MaruStudy" else "Could not save the PNG"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        RibbonBar {
            RibbonButton(
                icon = Icons.Outlined.Functions,
                label = "Single",
                active = !bulkMode,
                onClick = { bulkMode = false },
            )
            RibbonButton(
                icon = Icons.Outlined.ViewList,
                label = "Bulk",
                active = bulkMode,
                onClick = { bulkMode = true },
            )
            RibbonSeparator()
            if (bulkMode) {
                RibbonButton(
                    icon = Icons.Filled.Add,
                    label = "Add subject",
                    onClick = {
                        bulkSubjects.add(BulkSubject(id = nextBulkId))
                        nextBulkId += 1
                    },
                )
                RibbonButton(
                    icon = Icons.Outlined.DeleteSweep,
                    label = "Clear",
                    onClick = {
                        bulkSubjects.clear()
                        bulkSubjects.add(BulkSubject(id = nextBulkId))
                        nextBulkId += 1
                    },
                )
                RibbonSeparator()
            }
            RibbonButton(
                icon = Icons.Outlined.Save,
                label = "Save PNG",
                onClick = { exportPng() },
            )
            RibbonButton(
                icon = Icons.Outlined.OpenInNew,
                label = "TUP ERS",
                onClick = { openTupErs(context) },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (!bulkMode) {
            item {
                GlassCard {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionLabel("Inputs")
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            GradeInput(
                                value = prelim,
                                onValueChange = { prelim = it },
                                label = "Prelim",
                                modifier = Modifier.weight(1f),
                            )
                            GradeInput(
                                value = midterm,
                                onValueChange = { midterm = it },
                                label = "Midterm",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        MaruTextField(
                            value = course,
                            onValueChange = { course = it },
                            label = "Course / subject",
                            placeholder = "e.g. MATH 101",
                        )
                    }
                }
            }

            item {
                ResultCard(
                    required = required,
                    course = course,
                    onCopy = ::copyGrade,
                )
            }

            item {
                SolverNote()
            }
        } else {
                items(bulkSubjects, key = { it.id }) { subject ->
                    Box(Modifier.animateItem()) {
                        BulkSubjectCard(
                            subject = subject,
                            canDelete = bulkSubjects.size > 1,
                            onChange = { updated ->
                                val index = bulkSubjects.indexOfFirst { it.id == updated.id }
                                if (index >= 0) bulkSubjects[index] = updated
                            },
                            onDelete = { bulkSubjects.removeAll { it.id == subject.id } },
                        )
                    }
                }

            item {
                SolverNote()
            }
        }
        }
    }
}

@Composable
private fun GradeInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    MaruTextField(
        value = value,
        onValueChange = { onValueChange(normalizeGradeInput(it)) },
        label = label,
        placeholder = "5.0",
        keyboardType = KeyboardType.Decimal,
        modifier = modifier,
        trailingIcon = if (value.isNotEmpty()) {
            {
                Box(
                    Modifier.size(28.dp).clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear $label",
                        tint = MaruTextFaint,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ResultCard(
    required: Double?,
    course: String,
    onCopy: () -> Unit,
) {
    val difficulty = difficultyFor(required)
    val accent = difficulty?.color ?: MaruBorderSoft

    GlassCard(border = accent.copy(alpha = 0.55f)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    title = "Required endterm",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                if (required != null) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clickable(onClick = onCopy),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy grade",
                            tint = MaruTextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (course.isNotBlank()) {
                Text(course.trim(), color = MaruTextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                formatRequiredEndterm(required),
                color = if (required == null) MaruTextFaint else accent,
                fontSize = if (required != null && required < 0) 24.sp else 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                lineHeight = if (required != null && required < 0) 30.sp else 48.sp,
            )
            if (difficulty != null) {
                Box(
                    Modifier
                        .background(difficulty.color.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        difficulty.label.uppercase(),
                        color = difficulty.color,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BulkSubjectCard(
    subject: BulkSubject,
    canDelete: Boolean,
    onChange: (BulkSubject) -> Unit,
    onDelete: () -> Unit,
) {
    val required = computeRequiredEndterm(subject.prelim, subject.midterm)
    val difficulty = difficultyFor(required)

    GlassCard {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaruTextField(
                    value = subject.course,
                    onValueChange = { onChange(subject.copy(course = it)) },
                    label = "Course / subject",
                    placeholder = "Subject",
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .size(36.dp)
                            .clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Remove subject",
                            tint = MaruDanger,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GradeInput(
                    value = subject.prelim,
                    onValueChange = { onChange(subject.copy(prelim = it)) },
                    label = "Prelim",
                    modifier = Modifier.weight(1f),
                )
                GradeInput(
                    value = subject.midterm,
                    onValueChange = { onChange(subject.copy(midterm = it)) },
                    label = "Midterm",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "REQUIRED ENDTERM",
                    color = MaruTextFaint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatRequiredEndterm(required),
                    color = difficulty?.color ?: MaruTextFaint,
                    fontSize = if (required != null && required < 0) 13.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (difficulty != null && required != null && required >= 0) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        difficulty.label.uppercase(),
                        color = difficulty.color,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SolverNote() {
    InfoPanel(title = "How the grade is computed") {
        InfoLine("Endterm carries 40%. Prelim and midterm carry 30% each.")
        InfoLine("Formula: (4.85 - 0.3 × prelim - 0.3 × midterm) / 0.4")
        InfoLine("Blank inputs count as 5.0. Results round to one decimal and cap at 10.0.")
        InfoLine("A negative result means the endterm no longer matters: you already passed.")
    }
}

private fun openTupErs(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://ers.tup.edu.ph/aims/students/"))
        )
    }.onFailure {
        Toast.makeText(context, "Could not open the TUP ERS page.", Toast.LENGTH_SHORT).show()
    }
}
