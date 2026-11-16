package com.godbless.lockin.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.LudoGame
import com.godbless.lockin.ludo.LudoPalette
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay

/** A tiny board: four coloured corners, with a dot for each token home. Empty seats are dim. */
@Composable
private fun MiniLudo(g: LudoGame, modifier: Modifier) {
    Canvas(modifier) {
        val h = size.width / 2f
        for (s in 0..3) {
            val x = if (s == 1 || s == 2) h else 0f
            val y = if (s >= 2) h else 0f
            val on = g.seat(s)?.taken == true
            val c = if (on) LudoPalette.c(s) else LudoPalette.c(s).copy(alpha = 0.25f)
            drawRoundRect(c, Offset(x + 1.5f, y + 1.5f), Size(h - 3f, h - 3f), CornerRadius(5.dp.toPx()))
            if (on) {
                val home = (0..3).count { g.tokens[s * 4 + it] == 56 }
                for (k in 0..3) {
                    val cx = x + h * (if (k % 2 == 0) 0.32f else 0.68f)
                    val cy = y + h * (if (k < 2) 0.32f else 0.68f)
                    drawCircle(if (k < home) Color.White else Color.Black.copy(alpha = 0.35f), h * 0.11f, Offset(cx, cy))
                }
            }
        }
    }
}

@Composable
private fun LudoCardRow(g: LudoGame, title: String, detail: String, action: String, hot: Boolean, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val accent = if (g.playing) LudoPalette.c(g.turn) else LockInColors.Lime
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.5.dp, if (hot) accent else LockInColors.Graphite, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "$title. $detail. Open Ludo." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniLudo(g, Modifier.size(46.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = LockInText.Label.copy(color = if (hot) accent else LockInColors.Snow, fontSize = 15.sp), maxLines = 1)
            Text(detail, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1)
        }
        Text(
            action,
            style = LockInText.Label.copy(color = if (hot) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (hot) LockInColors.Lime else LockInColors.Graphite)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun homeOf(g: LudoGame, seat: Int) = (0..3).count { g.tokens[seat * 4 + it] == 56 }

/** The pair chat's Ludo card: whose turn, how many tokens each of you has home. */
@Composable
internal fun ChatLudoCard(partner: String, refreshKey: Any?, onOpen: () -> Unit) {
    val repo = LocalLudo.current ?: return
    var game by remember { mutableStateOf<LudoGame?>(null) }
    LaunchedEffect(refreshKey) {
        while (true) {
            repo.peek("pair").onSuccess { game = it }
            delay(4000)
        }
    }
    val g = game
    AnimatedVisibility(
        visible = g != null && !g.none,
        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
    ) {
        val shown = g ?: return@AnimatedVisibility
        val me = shown.meSeat
        val them = if (me == 0) 2 else 0
        val title = when {
            shown.over -> if (shown.seat(me)?.place == 1) "You won at Ludo 👑" else "$partner won at Ludo"
            shown.myTurn -> "Your turn at Ludo"
            else -> "$partner's turn at Ludo"
        }
        val detail = "You ${homeOf(shown, me.coerceAtLeast(0))} of 4 home · $partner ${homeOf(shown, them)} of 4"
        LudoCardRow(shown, title, detail, if (shown.over) "Again" else if (shown.myTurn) "Roll" else "Watch", shown.myTurn, onOpen)
    }
}

/** The crew chat's Ludo table: seats free to take, or the game going on. */
@Composable
internal fun CrewLudoCard(refreshKey: Any?, onOpen: () -> Unit) {
    val repo = LocalLudo.current ?: return
    var game by remember { mutableStateOf<LudoGame?>(null) }
    LaunchedEffect(refreshKey) {
        while (true) {
            repo.peek("crew").onSuccess { game = it }
            delay(5000)
        }
    }
    val g = game
    AnimatedVisibility(
        visible = g != null && !g.none,
        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 500f)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
    ) {
        val shown = g ?: return@AnimatedVisibility
        val n = shown.seated.size
        val seatedMe = shown.meSeat >= 0
        val title = when {
            shown.lobby -> "Ludo table · $n of 4 seats taken"
            shown.over -> "${shown.seats.firstOrNull { it.place == 1 }?.let { if (it.seat == shown.meSeat) "You" else it.first } ?: "Someone"} won at Ludo"
            shown.myTurn -> "Your turn at Ludo"
            else -> "Ludo in play"
        }
        val detail = when {
            shown.lobby && !seatedMe -> "Grab a seat before it starts."
            shown.lobby -> "Waiting for the host to start."
            else -> shown.seated.filter { !it.left }.joinToString(" · ") { (if (it.seat == shown.meSeat) "You" else it.first) + " ${homeOf(shown, it.seat)}/4" }
        }
        val action = when {
            shown.lobby && !seatedMe && n < 4 -> "Join"
            shown.myTurn -> "Roll"
            seatedMe -> "Open"
            else -> "Watch"
        }
        LudoCardRow(shown, title, detail, action, shown.myTurn || (shown.lobby && !seatedMe && n < 4), onOpen)
    }
}
