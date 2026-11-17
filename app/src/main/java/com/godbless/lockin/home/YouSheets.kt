@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.godbless.lockin.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInField
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * A card that slides up from the bottom of Home, over the tab bar and clear of the gesture bar. Like the Start sheet it
 * is part of Home's own overlay stack rather than a separate window. Taps on the dim around it close it.
 */
@Composable
internal fun OverlaySheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dim by animateFloatAsState(if (visible) 1f else 0f, tween(260, easing = UiGlide), label = "sheet-dim")
    if (dim > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * dim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            val top = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(top)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, top)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(LockInColors.Rim))
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Confirm
// ---------------------------------------------------------------------------------------------

/** A question worth a second tap: sign out, delete an account, clear things. */
internal class ConfirmSpec(
    val title: String,
    val body: String,
    val action: String,
    val danger: Boolean = true,
    val onConfirm: () -> Unit,
)

@Composable
internal fun ConfirmSheet(spec: ConfirmSpec?, onDismiss: () -> Unit) {
    // Keep showing the last question while the sheet slides away.
    var last by remember { mutableStateOf<ConfirmSpec?>(null) }
    if (spec != null) last = spec
    val shown = spec ?: last
    OverlaySheet(visible = spec != null, onDismiss = onDismiss) {
        if (shown != null) {
            Text(shown.title, style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
            Spacer(Modifier.height(6.dp))
            Text(shown.body, style = LockInText.Small.copy(color = LockInColors.Body))
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(if (shown.danger) LockInColors.Flare else LockInColors.Lime)
                    .springClick { onDismiss(); shown.onConfirm() },
                contentAlignment = Alignment.Center,
            ) {
                Text(shown.action, style = LockInText.Label.copy(color = if (shown.danger) LockInColors.Ink else LockInColors.OnLime, fontSize = 16.sp))
            }
            Spacer(Modifier.height(10.dp))
            LockInButton("Cancel", onClick = onDismiss, kind = ButtonKind.Outline)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Goal
// ---------------------------------------------------------------------------------------------

private val GoalPresets = listOf(30, 60, 90, 120, 180, 240)
private const val GoalMin = 15
private const val GoalMax = 960

/** Today's goal, rest days, and presets, kept in a draft until you press Save, so a stray tap never changes your goal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalSheet(visible: Boolean, plans: PlanState, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    OverlaySheet(visible, onDismiss) {
        val was = remember { plans.goalMinutes }
        val wasRest = remember { plans.restMask }
        var draft by remember { mutableIntStateOf(was) }
        var rest by remember { mutableIntStateOf(wasRest) }
        fun set(v: Int) {
            val next = v.coerceIn(GoalMin, GoalMax)
            if (next != draft) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            draft = next
        }

        Text("Daily goal", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
        Text("How long you want to lock in each day.", style = LockInText.Small.copy(color = LockInColors.Mist))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Stepper(Glyph.Minus, "Shorter goal", draft > GoalMin) { set(draft - 15) }
            // The number rolls: up for a longer goal, down for a shorter one.
            AnimatedContent(
                targetState = draft,
                modifier = Modifier.widthIn(min = 150.dp).padding(horizontal = 12.dp),
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(dampingRatio = 0.78f, stiffness = 420f)) { if (up) it else -it } togetherWith
                        slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 520f)) { if (up) -it else it })
                        .using(SizeTransform(clip = true))
                },
                contentAlignment = Alignment.Center,
                label = "goal-value",
            ) { v ->
                Text(
                    formatMinutes(v),
                    style = LockInText.Title.copy(fontSize = 36.sp, lineHeight = 42.sp),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Stepper(Glyph.Plus, "Longer goal", draft < GoalMax) { set(draft + 15) }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalPresets.forEach { m -> Chip(formatMinutes(m), picked = draft == m) { set(m) } }
        }
        // The undo line opens and closes (the sheet grows with it) instead of popping in and making the sheet jump.
        var lastWas by remember { mutableIntStateOf(was) }
        AnimatedVisibility(
            visible = draft != was,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { -it / 2 },
            exit = shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 520f)) + slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 520f)) { -it / 2 },
        ) {
            lastWas = was
            Text(
                "Was ${formatMinutes(lastWas)} · Undo",
                style = LockInText.Small.copy(color = LockInColors.Lime),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { set(was) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Rest days", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 15.sp))
        Text("A day you rest on purpose never breaks your streak.", style = LockInText.Small.copy(color = LockInColors.Mist))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { d ->
                val bit = 1 shl (d.value - 1)
                val on = rest and bit != 0
                // Lime fills the day from its centre, springing; the letter changes colour with it.
                val fill by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 520f), label = "rest-day")
                val letter by animateColorAsState(if (on) LockInColors.OnLime else LockInColors.Silver, tween(160, easing = UiGlide), label = "rest-day-text")
                val lime = LockInColors.Lime
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LockInColors.Graphite)
                        .drawBehind { if (fill > 0.01f) drawCircle(lime, radius = size.minDimension / 2f * fill) }
                        .clickable(role = Role.Checkbox) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            rest = rest xor bit
                        }
                        .semantics {
                            contentDescription = d.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (on) ", rest day" else ""
                            selected = on
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = LockInText.Label.copy(color = letter, fontSize = 14.sp),
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        LockInButton(
            "Save",
            onClick = {
                if (draft != was) plans.setGoal(draft)
                if (rest != wasRest) plans.setRestMask(rest)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDismiss()
            },
            enabled = draft != was || rest != wasRest,
        )
    }
}

@Composable
private fun Stepper(glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) LockInColors.Rim else LockInColors.Graphite, CircleShape)
            .springClick(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, if (enabled) LockInColors.Snow else LockInColors.Edge, boxSize = 20.dp, strokeWidth = 2.2f) }
}

@Composable
private fun Chip(text: String, picked: Boolean, onClick: () -> Unit) {
    // Picked: lime grows out from the chip's middle with a little spring; unpicked: it shrinks back.
    val fill by animateFloatAsState(if (picked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "chip-fill")
    val rim by animateColorAsState(if (picked) LockInColors.Lime else LockInColors.Rim, tween(180, easing = UiGlide), label = "chip-rim")
    val ink by animateColorAsState(if (picked) LockInColors.OnLime else LockInColors.Snow, tween(160, easing = UiGlide), label = "chip-text")
    val lime = LockInColors.Lime
    Box(
        Modifier
            .height(44.dp)
            .clip(CircleShape)
            .drawBehind {
                if (fill > 0.01f) {
                    val w = size.width * fill
                    val h = size.height * fill
                    drawRoundRect(lime, topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f), size = Size(w, h), cornerRadius = CornerRadius(h / 2f, h / 2f))
                }
            }
            .border(1.5.dp, rim, CircleShape)
            .springClick(onClick = onClick)
            .padding(horizontal = 18.dp)
            .semantics { selected = picked },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = LockInText.Label.copy(color = ink, fontSize = 14.sp)) }
}

// ---------------------------------------------------------------------------------------------
// Avatar and what you call your partner
// ---------------------------------------------------------------------------------------------

/** Pick an illustrated avatar (or a colour and symbol) for yourself, which your partner and crew see, and a private nickname for your partner (kept on this phone). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvatarSheet(visible: Boolean, name: String, partnerId: String?, partnerRealName: String, onDismiss: () -> Unit) {
    OverlaySheet(visible, onDismiss) {
        val haptic = LocalHapticFeedback.current
        var color by remember { mutableIntStateOf(Identity.avatarColor) }
        var glyph by remember { mutableStateOf(Identity.avatarGlyph) }
        var style by remember { mutableStateOf(Identity.avatarStyle) }
        var look by remember { mutableStateOf(Identity.avatarLook) }
        var seed by remember { mutableStateOf(Identity.avatarSeed.ifBlank { Identity.defaultSeed }.ifBlank { DiceBear.seedFor(name) }) }
        var options by remember { mutableStateOf(listOf(seed) + List(7) { DiceBear.randomSeed() }) }
        var spins by remember { mutableIntStateOf(0) }
        var nick by remember { mutableStateOf(Identity.nickname(partnerId).orEmpty()) }
        fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        val styleLabel = DiceBear.Styles.firstOrNull { it.first == style }?.second
        val bump = remember { androidx.compose.animation.core.Animatable(1f) }
        androidx.compose.runtime.LaunchedEffect(style, seed, look) {
            bump.snapTo(0.9f)
            bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
        val spin by animateFloatAsState(spins * 360f, tween(520, easing = UiGlide), label = "shuffleSpin")

        Text("Your avatar", style = LockInText.Title.copy(fontSize = 26.sp, lineHeight = 32.sp))
        Text("This is how your partner and crew see you.", style = LockInText.Small.copy(color = LockInColors.Mist))
        Spacer(Modifier.height(20.dp))

        // The preview.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(148.dp)
                    .drawBehind {
                        drawCircle(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(LockInColors.Lime.copy(alpha = 0.22f), Color.Transparent),
                                radius = size.minDimension / 2f,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(120.dp)
                        .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                        .clip(CircleShape)
                        .background(LockInColors.Slate)
                        .border(3.dp, LockInColors.Lime, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape),
                ) {
                    if (style.isNotEmpty()) {
                        DiceImage(style, seed, look, Modifier.fillMaxSize())
                    } else {
                        Avatar(
                            name, boxSize = 110.dp, filled = true, ring = LockInColors.Slate,
                            tint = AvatarColors.getOrNull(color), glyph = glyph.ifBlank { null },
                            me = true, previewStyle = "", previewSeed = seed,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            name.trim().ifEmpty { "You" },
            style = LockInText.Label.copy(fontSize = 18.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when {
                style.isEmpty() -> "Colour and symbol"
                else -> listOfNotNull(styleLabel, when (look) { "m" -> "Male"; "f" -> "Female"; else -> null }).joinToString(" · ")
            },
            style = LockInText.Small.copy(color = LockInColors.Mist),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Spacer(Modifier.height(22.dp))

        // Male, Female or everything.
        LookSegments(look, onChange = { picked ->
            if (picked != look) {
                tick()
                look = picked
                // Keep a style that can be steered; otherwise move to the first one that can.
                if (style.isNotEmpty() && picked.isNotEmpty() && !DiceBear.supportsLook(style)) style = DiceBear.Styles.first { DiceBear.supportsLook(it.first) }.first
            }
        })
        Spacer(Modifier.height(20.dp))

        // Style.
        Text("Style", style = LockInText.Label.copy(fontSize = 15.sp, color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        AnimatedContent(
            targetState = look,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(120)) },
            label = "styleRow",
        ) { l ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DiceBear.Styles.filter { l.isEmpty() || DiceBear.supportsLook(it.first) }.forEach { (id, label) ->
                    StyleCard(id, label, seed, l, chosen = style == id, onClick = { tick(); style = id })
                }
                if (l.isEmpty()) {
                    // Classic: a colour and a symbol.
                    Column(
                        Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { tick(); style = "" }
                            .padding(vertical = 6.dp)
                            .semantics { contentDescription = "Colour and symbol"; selected = style.isEmpty() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(AvatarColors.getOrNull(color) ?: LockInColors.Graphite)
                                .then(if (style.isEmpty()) Modifier.border(3.dp, LockInColors.Lime, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { Text(glyph.ifBlank { name.trim().take(1).uppercase().ifEmpty { "A" } }, fontSize = 24.sp, color = if (AvatarColors.getOrNull(color) != null) LockInColors.Ink else LockInColors.Snow) }
                        Text("Classic", style = LockInText.Small.copy(fontSize = 12.sp, color = if (style.isEmpty()) LockInColors.Lime else LockInColors.Silver), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        // Look choices for the chosen style, or the colour and symbol editors for Classic.
        AnimatedVisibility(
            visible = style.isNotEmpty(),
            enter = fadeIn(tween(220)) + expandVertically(tween(300)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
        ) {
            Column {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose a look", style = LockInText.Label.copy(fontSize = 15.sp, color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp))
                        Text("Tap one, or shuffle for more.", style = LockInText.Small.copy(color = LockInColors.Mist), modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(LockInColors.Graphite)
                            .clickable(role = Role.Button) {
                                tick()
                                spins++
                                options = List(8) { DiceBear.randomSeed() }
                                seed = options.first()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .semantics { contentDescription = "Shuffle looks" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("\uD83C\uDFB2", fontSize = 16.sp, modifier = Modifier.graphicsLayer { rotationZ = spin })
                        Text("Shuffle", style = LockInText.Label.copy(fontSize = 14.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                options.chunked(4).forEachIndexed { row, group ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        group.forEachIndexed { i, s ->
                            LookCell(style, s, look, row * 4 + i, chosen = s == seed, onClick = { tick(); seed = s })
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = style.isEmpty(),
            enter = fadeIn(tween(220)) + expandVertically(tween(300)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
        ) {
            Column {
                Spacer(Modifier.height(18.dp))
                Text("Colour", style = LockInText.Label.copy(fontSize = 15.sp, color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarColors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(if (color == i) Modifier.border(3.dp, LockInColors.Snow, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { tick(); color = i }
                                .semantics { contentDescription = "Colour ${i + 1}"; selected = color == i },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Symbol", style = LockInText.Label.copy(fontSize = 15.sp, color = LockInColors.Silver), modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (glyph.isBlank()) LockInColors.LimeWash else LockInColors.Graphite)
                            .clickable(role = Role.RadioButton) { tick(); glyph = "" }
                            .semantics { contentDescription = "Use my initial"; selected = glyph.isBlank() },
                        contentAlignment = Alignment.Center,
                    ) { Text(name.trim().take(1).uppercase().ifEmpty { "A" }, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp)) }
                    AvatarGlyphs.forEach { g ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (glyph == g) LockInColors.LimeWash else LockInColors.Graphite)
                                .clickable(role = Role.RadioButton) { tick(); glyph = g }
                                .semantics { contentDescription = "Symbol $g"; selected = glyph == g },
                            contentAlignment = Alignment.Center,
                        ) { Text(g, fontSize = 20.sp) }
                    }
                }
            }
        }
        if (partnerId != null) {
            Spacer(Modifier.height(20.dp))
            LockInField(
                label = "What you call $partnerRealName",
                value = nick,
                onValueChange = { if (it.length <= 24) nick = it },
                placeholder = partnerRealName,
                imeAction = ImeAction.Done,
            )
            Text("A private nickname. $partnerRealName never sees it.", style = LockInText.Small, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        LockInButton("Save avatar", onClick = {
            Identity.setAvatar(color, glyph, style, seed, look)
            if (partnerId != null) Identity.setNickname(partnerId, nick)
            onDismiss()
        })
    }
}
