package com.mytupv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytupv.app.ui.screens.CalculatorScreen
import com.mytupv.app.ui.screens.GradesScreen
import com.mytupv.app.ui.screens.LinksScreen

private val BgDeep      = Color(0xFF07040F)
private val PanelBg     = Color(0xFF0E0A1A)
private val PanelBorder = Color(0xFF1E1A2E)
private val Accent      = Color(0xFF788CFF)
private val TextPrimary = Color(0xFFF0EEFF)
private val TextMuted   = Color(0x99EBEBF5)

private data class TabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TABS = listOf(
    TabItem("Grades",     Icons.Filled.School,    Icons.Outlined.School),
    TabItem("Calculator", Icons.Filled.Calculate, Icons.Outlined.Calculate),
    TabItem("Links",      Icons.Filled.Link,      Icons.Outlined.Link),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyTupvApp()
        }
    }
}

@Composable
fun MyTupvApp() {
    var selectedTab by remember { mutableStateOf(0) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background    = BgDeep,
            surface       = PanelBg,
            primary       = Accent,
            onPrimary     = Color(0xFF0B071A),
            onBackground  = TextPrimary,
            onSurface     = TextPrimary,
            surfaceVariant = PanelBorder,
        )
    ) {
        Scaffold(
            containerColor = BgDeep,
            bottomBar = {
                NavigationBar(
                    containerColor = PanelBg,
                    tonalElevation = 0.dp,
                ) {
                    TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick  = { selectedTab = index },
                            icon = {
                                Icon(
                                    if (selectedTab == index) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = Accent,
                                selectedTextColor   = Accent,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor      = Color(0x1A788CFF),
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BgDeep)
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> GradesScreen()
                    1 -> CalculatorScreen()
                    2 -> LinksScreen()
                }
            }
        }
    }
}
