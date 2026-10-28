package com.maru.study

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Maru applet design tokens: square corners, acrylic dark panels,
// hairline borders, single blue accent.
val MaruPageTop = Color(0xFF18191A)
val MaruPageMid = Color(0xFF111315)
val MaruPageBottom = Color(0xFF0B0D0F)

val MaruPanel = Color(0xEB202327)
val MaruPanelAlt = Color(0xF5242526)
val MaruPanelDeep = Color(0xF4111213)
val MaruPanelSolid = Color(0xFF202327)
val MaruPanelDeepSolid = Color(0xFF111213)
val MaruBorderSoft = Color(0xFF3A3B3C)
val MaruBorderStrong = Color(0xFF4B4C4E)

val MaruAccent = Color(0xFF2A7FFF)
val MaruAccentDeep = Color(0xFF2374E1)
val MaruAccentSoft = Color(0x6B2374E1)
val MaruAccentBorder = Color(0xB83E8FFF)
val MaruAccentGlow = Color(0x332A7FFF)

val MaruTextStrong = Color(0xFFE4E6EB)
val MaruTextMuted = Color(0xFFB0B3B8)
val MaruTextFaint = Color(0xFF82858B)
val MaruDanger = Color(0xFFFF8D8D)
val MaruSuccess = Color(0xFF6FCF97)

// The design language zeroes every radius.
val MaruShape = RoundedCornerShape(0.dp)
val MaruInputBorder = 1.dp

@Composable
fun MaruStudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MaruAccent,
            onPrimary = Color.White,
            secondary = MaruAccentDeep,
            background = MaruPageMid,
            surface = MaruPanel,
            onBackground = MaruTextStrong,
            onSurface = MaruTextStrong,
            error = MaruDanger,
        ),
        content = content,
    )
}

@Composable
fun MaruBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaruPageTop,
                    0.55f to MaruPageMid,
                    1f to MaruPageBottom,
                )
            )
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    border: Color = MaruBorderSoft,
    background: Color = MaruPanel,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = MaruShape,
            color = background,
            border = BorderStroke(1.dp, border),
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = MaruShape,
            color = background,
            border = BorderStroke(1.dp, border),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SectionLabel(
    title: String,
    accent: Color = MaruAccent,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            title.uppercase(),
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaruPanelDeep)
            .border(1.dp, MaruBorderSoft)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (active) MaruAccentDeep else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option).uppercase(),
                    color = if (active) Color.White else MaruTextMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun MaruTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label.uppercase(), fontSize = 11.5.sp, letterSpacing = 0.8.sp) },
        placeholder = placeholder?.let { { Text(it, color = MaruTextFaint, fontSize = 13.5.sp) } },
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        readOnly = readOnly,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = MaruShape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaruTextStrong, fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaruAccentBorder,
            unfocusedBorderColor = MaruBorderSoft,
            focusedContainerColor = MaruPanelDeep,
            unfocusedContainerColor = MaruPanelDeep,
            cursorColor = MaruAccent,
            focusedLabelColor = MaruAccent,
            unfocusedLabelColor = MaruTextMuted,
            focusedTextColor = MaruTextStrong,
            unfocusedTextColor = MaruTextStrong,
            focusedPlaceholderColor = MaruTextFaint,
            unfocusedPlaceholderColor = MaruTextFaint,
        ),
    )
}

@Composable
fun MaruPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaruShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaruAccentDeep,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF1B1C1E),
            disabledContentColor = MaruTextFaint,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.3.sp)
    }
}

@Composable
fun MaruGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaruShape,
        border = BorderStroke(1.dp, MaruBorderSoft),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaruTextStrong),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    accent: Color = MaruAccent,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier, border = MaruBorderSoft) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                label.uppercase(),
                color = accent,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
            Text(value, color = MaruTextStrong, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EmptyStateCard(
    title: String,
    subtitle: String,
    accent: Color = MaruAccent,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier, border = accent.copy(alpha = 0.28f)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = MaruTextMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun MaruDivider(modifier: Modifier = Modifier, color: Color = MaruBorderSoft) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun MaruIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaruTextMuted,
) {
    Box(
        modifier
            .size(42.dp)
            .background(MaruPanelDeep)
            .border(1.dp, MaruBorderSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun InfoPanel(
    title: String = "How this works",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaruTextFaint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = MaruTextMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide details" else "Show details",
                tint = MaruTextFaint,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            MaruDivider()
            Column(
                Modifier.padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                content = content,
            )
        }
    }
}

@Composable
fun InfoLine(text: String) {
    Text(text, color = MaruTextMuted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
fun RibbonBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaruPanelDeepSolid)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
        MaruDivider()
    }
}

@Composable
fun RowScope.RibbonButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (active && enabled) MaruAccentSoft else Color.Transparent,
        animationSpec = tween(180),
        label = "ribbon-background",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> MaruTextFaint.copy(alpha = 0.5f)
            active -> MaruAccent
            else -> MaruTextMuted
        },
        animationSpec = tween(180),
        label = "ribbon-tint",
    )
    Row(
        Modifier
            .height(36.dp)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = tint,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun RibbonSeparator() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaruBorderSoft))
}

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                eyebrow.uppercase(),
                color = MaruAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Text(title, color = MaruTextStrong, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            if (subtitle != null) {
                Text(subtitle, color = MaruTextMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
fun TagLabel(
    text: String,
    color: Color = MaruTextMuted,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .border(1.dp, MaruBorderSoft)
            .background(MaruPanelDeep)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text.uppercase(),
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
        )
    }
}
