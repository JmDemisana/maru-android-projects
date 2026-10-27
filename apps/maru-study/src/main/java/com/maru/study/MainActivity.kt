package com.maru.study

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaruStudyTheme {
                MaruStudyApp()
            }
        }
    }
}

enum class StudySection(val label: String, val icon: ImageVector) {
    SCHEDULE("Schedule", Icons.Outlined.CalendarMonth),
    GRADES("TUP Grade Solver", Icons.Outlined.Functions),
    HOMEWORK("Homework", Icons.Outlined.Assignment),
    NANAMI("Nanami AI", Icons.Outlined.AutoAwesome),
}

@Composable
private fun MaruStudyApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val studyStore = remember { StudyStore(context) }
    val homeworkStore = remember { HomeworkStore(context) }
    val reminderStore = remember { ReminderStore(context) }

    var document by remember { mutableStateOf(studyStore.load()) }
    var homework by remember { mutableStateOf(homeworkStore.load()) }
    var section by rememberSaveable { mutableStateOf(StudySection.SCHEDULE) }
    var newClassSignal by rememberSaveable { mutableStateOf(0) }

    fun updateDocument(next: ScheduleDocument) {
        document = next
        studyStore.save(next)
        MaruReminders.scheduleClasses(context, next, reminderStore.load())
    }

    fun updateHomework(next: List<HomeworkTask>) {
        homework = next
        homeworkStore.save(next)
        next.filter { it.done || !it.reminder }.forEach { MaruReminders.cancelHomework(context, it) }
        next.filter { !it.done && it.reminder }.forEach { MaruReminders.scheduleHomework(context, it) }
    }

    LaunchedEffect(Unit) {
        MaruReminders.ensureChannels(context)
        MaruReminders.scheduleClasses(context, document, reminderStore.load())
        homework.filter { !it.done && it.reminder }.forEach { task ->
            MaruReminders.scheduleHomework(context, task)
        }
    }

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun selectSection(next: StudySection) {
        section = next
        scope.launch { drawerState.close() }
    }

    fun addSubjectInSchedule() {
        newClassSignal++
        selectSection(StudySection.SCHEDULE)
    }

    MaruBackground {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = section != StudySection.NANAMI,
            drawerContent = {
                MaruDrawer(
                    selected = section,
                    onSelect = ::selectSection,
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (section != StudySection.NANAMI) {
                    MaruTopBar(
                        title = section.label,
                        subtitle = when (section) {
                            StudySection.SCHEDULE -> when {
                                document.entries.isEmpty() -> "Add your first class"
                                else -> "${classCountLabel(document.entries.size)} this week"
                            }
                            StudySection.GRADES -> "Required endterm"
                            StudySection.HOMEWORK -> {
                                val pending = homework.count { !it.done }
                                if (pending == 0) "All clear" else "$pending pending"
                            }
                            StudySection.NANAMI -> null
                        },
                        onMenu = ::openDrawer,
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = section,
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 })
                                .togetherWith(fadeOut(tween(140)))
                        },
                        label = "section",
                    ) { current ->
                        when (current) {
                            StudySection.SCHEDULE -> ScheduleScreen(
                                document = document,
                                onDocumentChange = ::updateDocument,
                                newClassSignal = newClassSignal,
                            )

                            StudySection.GRADES -> GradeSolverScreen()

                            StudySection.HOMEWORK -> HomeworkScreen(
                                tasks = homework,
                                subjects = document.subjects(),
                                onTasksChange = ::updateHomework,
                                onAddSubject = ::addSubjectInSchedule,
                            )

                            StudySection.NANAMI -> NanamiWebScreen(
                                onExit = {
                                    section = StudySection.SCHEDULE
                                    openDrawer()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaruTopBar(
    title: String,
    subtitle: String?,
    onMenu: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaruPanelDeepSolid)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Open menu",
                    tint = MaruTextStrong,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaruTextStrong,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = MaruTextFaint,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        MaruDivider()
    }
}

@Composable
private fun MaruDrawer(
    selected: StudySection,
    onSelect: (StudySection) -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .width(292.dp)
            .fillMaxHeight(),
        drawerShape = MaruShape,
        drawerContainerColor = MaruPanelDeepSolid,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaruAccent))

            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            ) {
                Text(
                    "Maru Study",
                    color = MaruTextStrong,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
            }

            MaruDivider()
            Spacer(Modifier.height(8.dp))

            StudySection.entries.forEach { item ->
                DrawerItem(
                    section = item,
                    active = item == selected,
                    onClick = { onSelect(item) },
                )
            }

            Spacer(Modifier.weight(1f))
            MaruDivider(color = MaruBorderSoft.copy(alpha = 0.5f))
            Text(
                "Made with <3 by Maru and Nanami",
                color = MaruTextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun DrawerItem(
    section: StudySection,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) MaruAccentSoft else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(18.dp)
                .background(if (active) MaruAccent else Color.Transparent)
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            section.icon,
            contentDescription = null,
            tint = if (active) MaruAccent else MaruTextFaint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            section.label,
            color = if (active) MaruTextStrong else MaruTextMuted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
