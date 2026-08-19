package com.mytupv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// ── Theme colours (shared from GradesScreen) ──────────────
private val BgDeep      = Color(0xFF07040F)
private val PanelBg     = Color(0xFF0E0A1A)
private val PanelBorder = Color(0xFF1E1A2E)
private val Accent      = Color(0xFF788CFF)
private val AccentSoft  = Color(0x26788CFF)
private val TextPrimary = Color(0xFFF0EEFF)
private val TextMuted   = Color(0x99EBEBF5)
private val GreenPass   = Color(0xFF51CF66)
private val RedFail     = Color(0xFFFF6B6B)

// TUP grade formula: Required = (4.85 - 0.3*P - 0.3*M) / 0.4
private fun computeRequired(prelim: Double, midterm: Double): Double {
    return (4.85 - 0.3 * prelim - 0.3 * midterm) / 0.4
}

private data class BulkRow(
    val id: String = UUID.randomUUID().toString(),
    var subject: String = "",
    var prelim: String = "",
    var midterm: String = "",
)

@Composable
fun CalculatorScreen() {
    var mode by remember { mutableStateOf("single") } // "single" or "bulk"

    // Single mode state
    var sPrelim   by remember { mutableStateOf("") }
    var sMidterm  by remember { mutableStateOf("") }
    var sCourse   by remember { mutableStateOf("") }

    // Bulk mode state
    var bulkRows by remember { mutableStateOf(listOf(BulkRow())) }

    val sRequired: Double? = remember(sPrelim, sMidterm) {
        val p = sPrelim.toDoubleOrNull()
        val m = sMidterm.toDoubleOrNull()
        if (p != null && m != null) computeRequired(p, m) else null
    }

    LazyColumn(
        Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Title ─────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Calculate, null, tint = Accent, modifier = Modifier.size(28.dp))
                Column {
                    Text("Grade Calculator", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Required endterm grade solver", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        // ── Formula card ──────────────────────────────
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AccentSoft,
                border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.3f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("TUP Passing Formula", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Required = (4.85 − 0.3×Prelim − 0.3×Midterm) ÷ 0.4",
                        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("Minimum passing average: 4.85 (or 3.0 depending on your program)", color = TextMuted, fontSize = 11.sp)
                }
            }
        }

        // ── Mode toggle ───────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("single" to "Single Subject", "bulk" to "Bulk Mode").forEach { (m, label) ->
                    Surface(
                        onClick = { mode = m },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = if (mode == m) AccentSoft else PanelBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (mode == m) Accent.copy(0.6f) else PanelBorder)
                    ) {
                        Text(
                            label, color = if (mode == m) Accent else TextMuted,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── SINGLE MODE ───────────────────────────────
        if (mode == "single") {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PanelBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CalcTextField(sCourse, { sCourse = it }, "Subject Name (optional)", KeyboardType.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CalcTextField(sPrelim, { sPrelim = it.take(4) }, "Prelim", KeyboardType.Decimal, Modifier.weight(1f))
                            CalcTextField(sMidterm, { sMidterm = it.take(4) }, "Midterm", KeyboardType.Decimal, Modifier.weight(1f))
                        }
                    }
                }
            }

            // Result card
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PanelBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (sRequired != null) Accent.copy(0.3f) else PanelBorder)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Required Endterm", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (sRequired == null) {
                            Text("--", color = TextMuted, fontSize = 44.sp, fontWeight = FontWeight.Black)
                            Text("Enter prelim and midterm grades above", color = TextMuted, fontSize = 12.sp)
                        } else if (sRequired < 0) {
                            Text("✓", color = GreenPass, fontSize = 44.sp, fontWeight = FontWeight.Black)
                            Text("You already passed!", color = GreenPass, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        } else if (sRequired > 10) {
                            Text("!", color = RedFail, fontSize = 44.sp, fontWeight = FontWeight.Black)
                            Text("Grade no longer achievable (>${String.format("%.1f", sRequired)})", color = RedFail, fontSize = 13.sp)
                        } else {
                            val color = if (sRequired <= 5.0) GreenPass else RedFail
                            Text(String.format("%.1f", sRequired.coerceAtMost(10.0)), color = color, fontSize = 44.sp, fontWeight = FontWeight.Black)
                            Text(
                                if (sRequired <= 5.0) "Achievable — good luck, Senpai! 🎵"
                                else "Tough but aim high! 💪",
                                color = TextMuted, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // ── BULK MODE ─────────────────────────────────
        if (mode == "bulk") {
            items(bulkRows, key = { it.id }) { row ->
                val required = run {
                    val p = row.prelim.toDoubleOrNull()
                    val m = row.midterm.toDoubleOrNull()
                    if (p != null && m != null) computeRequired(p, m) else null
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PanelBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = row.subject,
                                onValueChange = { v ->
                                    bulkRows = bulkRows.map { if (it.id == row.id) it.copy(subject = v) else it }
                                },
                                placeholder = { Text("Subject name", color = TextMuted, fontSize = 12.sp) },
                                colors = calcFieldColors(),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { if (bulkRows.size > 1) bulkRows = bulkRows.filter { it.id != row.id } },
                                enabled = bulkRows.size > 1
                            ) {
                                Icon(Icons.Filled.Delete, null, tint = if (bulkRows.size > 1) RedFail else TextMuted)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = row.prelim,
                                onValueChange = { v -> bulkRows = bulkRows.map { if (it.id == row.id) it.copy(prelim = v.take(4)) else it } },
                                placeholder = { Text("Prelim", color = TextMuted, fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = calcFieldColors(),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp)
                            )
                            OutlinedTextField(
                                value = row.midterm,
                                onValueChange = { v -> bulkRows = bulkRows.map { if (it.id == row.id) it.copy(midterm = v.take(4)) else it } },
                                placeholder = { Text("Midterm", color = TextMuted, fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = calcFieldColors(),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp)
                            )
                            // Required result chip
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    required == null -> Color(0x14FFFFFF)
                                    required < 0 -> Color(0x1A51CF66)
                                    required > 10 -> Color(0x1AFF6B6B)
                                    else -> AccentSoft
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = when {
                                            required == null -> "--"
                                            required < 0 -> "Passed!"
                                            required > 10 -> ">10"
                                            else -> String.format("%.1f", required.coerceAtMost(10.0))
                                        },
                                        color = when {
                                            required == null -> TextMuted
                                            required < 0 -> GreenPass
                                            required > 10 -> RedFail
                                            else -> Accent
                                        },
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { bulkRows = bulkRows + BulkRow() },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PanelBg, contentColor = Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(0.4f))
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Subject", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CalcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp, color = TextMuted) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = calcFieldColors(),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = TextPrimary)
    )
}

@Composable
private fun calcFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor   = Color(0xFF07040F),
    unfocusedContainerColor = Color(0xFF07040F),
    focusedBorderColor      = Color(0xFF788CFF),
    unfocusedBorderColor    = Color(0xFF1E1A2E),
    focusedTextColor        = Color(0xFFF0EEFF),
    unfocusedTextColor      = Color(0xFFF0EEFF),
    focusedLabelColor       = Color(0xFF788CFF),
    unfocusedLabelColor     = Color(0x99EBEBF5),
)
