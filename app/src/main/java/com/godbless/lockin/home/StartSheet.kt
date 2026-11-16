package com.godbless.lockin.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.UiGlide

/**
 * What tapping Start on Home opens when you have a partner: lock in on your own, or lock your partner's phone.
 * It is part of Home's own overlay stack (like the activity list), not a separate window, so it sits above the tab
 * bar and keeps clear of the phone's gesture bar. The partner option is the same card as on the Partner tab, so a
 * running lock (time left, an unlock request) shows here too.
 */
@Composable
internal fun StartSheet(
    visible: Boolean,
    lock: LockState,
    onOwn: () -> Unit,
    onLockPartner: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dim by animateFloatAsState(if (visible) 1f else 0f, tween(260, easing = UiGlide), label = "start-dim")
    if (dim > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * dim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    val name = LocalPartnerName.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(340, easing = UiGlide)) { it },
            exit = slideOutVertically(tween(280, easing = UiGlide)) { it },
        ) {
            val top = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            val card = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(top)
                    .background(LockInColors.Slate)
                    .border(1.dp, LockInColors.Graphite, top)
                    // Swallow taps on the sheet so they don't reach the dim behind it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    // The sheet's colour runs down behind the gesture bar; its content stops above it.
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(LockInColors.Rim),
                )
                Spacer(Modifier.height(16.dp))
                Text("Start", style = LockInText.Title.copy(fontSize = 24.sp, lineHeight = 30.sp))
                Text("How do you want to lock in?", style = LockInText.Small.copy(color = LockInColors.Mist))
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(card)
                        .background(LockInColors.Graphite)
                        .clickable(role = Role.Button, onClick = onOwn)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(LockInColors.Slate),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Peak, LockInColors.Lime, boxSize = 22.dp, strokeWidth = 2f) }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("Lock in on your own", style = LockInText.Title.copy(fontSize = 19.sp, lineHeight = 24.sp))
                        Text(
                            "Your phone locks and your climb counts. $name sees you on the rope.",
                            style = LockInText.Small.copy(color = LockInColors.Mist),
                        )
                    }
                    GlyphIcon(Glyph.Chevron, LockInColors.Mist, boxSize = 18.dp, strokeWidth = 2f)
                }
                Spacer(Modifier.height(12.dp))
                LockEntryCard(lock, onOpen = onLockPartner)
            }
        }
    }
}
