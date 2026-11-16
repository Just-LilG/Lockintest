package com.godbless.lockin.study

import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.home.Glyph
import com.godbless.lockin.home.GlyphIcon
import com.godbless.lockin.ui.BackButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText

/** The study tools that can be opened on top of anything, including a lock screen. */
internal enum class Tool(val label: String, val glyph: Glyph) {
    Calculator("Calculator", Glyph.Calculator),
    Textbooks("Textbooks", Glyph.Book),
    Quiz("Quiz", Glyph.Cards),
}

/**
 * Three tiles that open the study tools. On a lock screen they are the only way out to anything but the
 * app itself; on Home they sit in a card with a line of detail under each name.
 */
@Composable
internal fun ToolDock(onOpen: (Tool) -> Unit, modifier: Modifier = Modifier, details: List<String>? = null, badges: Map<Tool, String> = emptyMap()) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tool.entries.forEachIndexed { i, tool ->
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val squeeze by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tool")
            val shape = RoundedCornerShape(20.dp)
            Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
                    .clip(shape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, shape)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { onOpen(tool) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlyphIcon(tool.glyph, LockInColors.Snow, boxSize = 24.dp)
                Spacer(Modifier.height(6.dp))
                Text(tool.label, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp))
                if (details != null) {
                    Text(details[i], style = LockInText.Small.copy(color = LockInColors.Mist, fontSize = 11.sp, lineHeight = 14.sp))
                }
            }
            badges[tool]?.let { b ->
                Text(
                    b,
                    style = LockInText.Label.copy(color = LockInColors.OnLime, fontSize = 10.sp, lineHeight = 12.sp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp).clip(CircleShape).background(LockInColors.Lime).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            }
        }
    }
}

/** Home's study kit as one slim row of three chips. Quiz shows a lime count when cards are due. */
@Composable
internal fun StudyKitRow(store: StudyStore, onOpen: (Tool) -> Unit, modifier: Modifier = Modifier) {
    val due = store.cards.count { store.isDue(it) }
    // Where you stopped reading, as one line under the chips: the kit becomes a way back in, not three buttons.
    val resume = store.reads.lastOrNull()?.let { r ->
        store.books.firstOrNull { it.id == r.book || it.title == r.book }?.let { it.title to r.page }
    }
    Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tool.entries.forEachIndexed { index, tool ->
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val squeeze by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "kit")
            // The chips pop up one after another, and the icon tips when you press.
            val pop = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(420L + index * 80L)
                pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
            }
            val tilt by animateFloatAsState(if (pressed) -12f else 0f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "tilt")
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .graphicsLayer {
                        val p = pop.value
                        scaleX = squeeze * (0.8f + 0.2f * p)
                        scaleY = squeeze * (0.8f + 0.2f * p)
                        translationY = (1f - p) * 22.dp.toPx()
                    }
                    .clip(shape)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, shape)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { onOpen(tool) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                GlyphIcon(tool.glyph, LockInColors.Snow, modifier = Modifier.graphicsLayer { rotationZ = tilt }, boxSize = 18.dp)
                Text(
                    if (tool == Tool.Textbooks) "Books" else tool.label,
                    style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 13.sp),
                    modifier = Modifier.padding(start = 7.dp),
                    maxLines = 1,
                )
                if (tool == Tool.Quiz && due > 0) {
                    Box(
                        Modifier.padding(start = 6.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(LockInColors.Lime).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$due", style = LockInText.Small.copy(color = LockInColors.OnLime, fontSize = 11.sp, lineHeight = 13.sp)) }
                }
            }
        }
    }
    if (resume != null) {
        Text(
            "Continue: ${resume.first}, page ${resume.second}",
            style = LockInText.Small.copy(color = LockInColors.Silver),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { onOpen(Tool.Textbooks) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    }
}

/** Home's card for the study tools, with how many books there are and how many cards are due. */
@Composable
internal fun StudyKitCard(store: StudyStore, onOpen: (Tool) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val due = store.cards.count { store.isDue(it) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .padding(16.dp),
    ) {
        Text("Study kit", style = LockInText.Label.copy(color = LockInColors.Silver))
        Text("Also on your lock screen.", style = LockInText.Small.copy(color = LockInColors.Mist))
        Spacer(Modifier.height(12.dp))
        ToolDock(
            onOpen = onOpen,
            details = listOf(
                "Scientific",
                if (store.books.size == 1) "1 book" else "${store.books.size} books",
                if (store.cards.isEmpty()) "No cards" else "$due due",
            ),
        )
    }
}

/**
 * The frame for the study screens that scroll: a back button and heading, then your content. It keeps
 * a minimum gap at the top, because on a lock screen the status bar is hidden and reports no height.
 */
@Composable
internal fun StudyScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    /** A screen laid out in two columns (see TwoColumns): on a big screen it gets the full wide width. */
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().coerceAtLeast(36.dp)
    Column(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(top = topPad)
            .navigationBarsPadding()
            .readable(if (wide && com.godbless.lockin.ui.twoPane()) Widths.Wide else Widths.Page)
            .padding(horizontal = if (wide && com.godbless.lockin.ui.twoPane()) 28.dp else 20.dp, vertical = 12.dp),
    ) {
        BackButton(onBack)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(title, style = LockInText.Headline.copy(fontSize = 38.sp))
            Text(subtitle, style = LockInText.Sub.copy(color = LockInColors.Silver))
        }
        Spacer(Modifier.height(20.dp))
        content()
        Spacer(Modifier.height(32.dp))
    }
}

/** A labelled text box for the study screens. */
@Composable
internal fun StudyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 54.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier) {
        Text(label, style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(shape)
                .background(LockInColors.Ink)
                .border(1.5.dp, if (focused) LockInColors.Lime else LockInColors.Graphite, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = LockInText.Input,
                cursorBrush = SolidColor(LockInColors.Lime),
                maxLines = if (number) 1 else 6,
                singleLine = number,
                keyboardOptions = if (number) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}
