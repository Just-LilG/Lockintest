package com.godbless.lockin.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.data.TttGame
import com.godbless.lockin.data.TttRepository
import com.godbless.lockin.lock.TicTacToe
import com.godbless.lockin.lock.WordDuel
import com.godbless.lockin.data.WdGame
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay

/**
 * The chat's game card: a small live board, whose turn it is, and a button to open the game. It looks at the game without
 * telling your partner you have it open (so their moves still reach you as a push), and refreshes when the chat gets a new
 * line ([refreshKey]) and every few seconds.
 */
@Composable
internal fun ChatGameCard(partner: String, refreshKey: Any?, onOpen: () -> Unit) {
    val repo = LocalTtt.current ?: return
    var game by remember { mutableStateOf<TttGame?>(null) }
    LaunchedEffect(refreshKey) {
        while (true) {
            repo.peek().onSuccess { game = it }
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
        val myTurn = shown.status == "playing" && shown.myTurn
        val shape = RoundedCornerShape(20.dp)
        val title = when {
            shown.status == "won" -> if (shown.winner == "me") "You won" else "$partner won"
            shown.status == "draw" -> "A draw"
            myTurn && shown.mustSlide -> "Your turn: move a mark"
            myTurn -> "Your turn"
            else -> "Waiting for $partner"
        }
        val detail = when {
            shown.over -> "${shown.winsMe} – ${shown.winsThem}  (you – $partner). Tap for a rematch."
            myTurn -> "Tic-tac-toe with $partner"
            else -> "Tic-tac-toe. They'll be told it's their move."
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.5.dp, if (myTurn) LockInColors.Lime else LockInColors.Graphite, shape)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = "$title. $detail. Open the game." },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniBoard(shown, Modifier.size(46.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = LockInText.Label.copy(color = if (myTurn || shown.winner == "me") LockInColors.Lime else LockInColors.Snow, fontSize = 15.sp), maxLines = 1)
                Text(detail, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1)
            }
            Text(
                if (shown.over) "Rematch" else if (myTurn) "Play" else "Open",
                style = LockInText.Label.copy(color = if (myTurn) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (myTurn) LockInColors.Lime else LockInColors.Graphite)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** The game as a little picture: nine squares and the marks on them. */
@Composable
private fun MiniBoard(g: TttGame, modifier: Modifier) {
    val mine = LockInColors.Lime
    val theirs = LockInColors.Snow
    val tile = LockInColors.Ink.copy(alpha = 0.6f)
    Canvas(modifier) {
        val cell = size.width / 3f
        val gap = 2.dp.toPx()
        for (i in 0 until 9) {
            val tl = Offset((i % 3) * cell + gap, (i / 3) * cell + gap)
            val sz = Size(cell - gap * 2, cell - gap * 2)
            drawRoundRect(tile, tl, sz, CornerRadius(4.dp.toPx()))
            val ch = g.board.getOrElse(i) { '.' }
            if (ch == '.') continue
            val c = Offset((i % 3) * cell + cell / 2f, (i / 3) * cell + cell / 2f)
            val color = if (ch.toString() == g.iAm) mine else theirs
            val r = cell * 0.22f
            val w = 2.2.dp.toPx()
            if (ch == 'X') {
                drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), w, StrokeCap.Round)
                drawLine(color, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), w, StrokeCap.Round)
            } else {
                drawCircle(color, r, c, style = Stroke(w))
            }
        }
    }
}

/** The game, full screen, slid up over the chat. Closing it goes back to the chat; the game carries on. */
@Composable
internal fun ChatGameScreen(partner: String, kind: String, onClose: () -> Unit) {
    val repo = LocalTtt.current
    val wd = LocalWd.current
    val live = LocalLive.current
    val ludo = LocalLudo.current
    if (kind == "ludo" && ludo != null) {
        // Ludo lays itself out edge to edge.
        com.godbless.lockin.ludo.LudoGameScreen(ludo, "pair", live, "You and $partner", onBack = onClose)
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(LockInColors.Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (repo == null || wd == null) {
            Text("Games aren't available right now.", style = LockInText.Small, modifier = Modifier.padding(24.dp))
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (kind == "wd") WordDuel(wd, partner, onBack = onClose, live = live)
                else TicTacToe(repo, partner, onBack = onClose, live = live)
            }
        }
    }
}

/** The chat's Word Duel card: the last few guesses as little coloured tiles, whose move it is, and a button to open the game. */
@Composable
internal fun ChatWordCard(partner: String, refreshKey: Any?, onOpen: () -> Unit) {
    val repo = LocalWd.current ?: return
    var game by remember { mutableStateOf<WdGame?>(null) }
    LaunchedEffect(refreshKey) {
        while (true) {
            repo.peek().onSuccess { game = it }
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
        val myTurn = shown.myTurn
        val shape = RoundedCornerShape(20.dp)
        val title = when {
            shown.status == "solved" -> if (shown.iSet) "$partner got your word" else "You got the word"
            shown.status == "failed" -> if (shown.iSet) "$partner ran out of tries" else "Out of tries"
            shown.status == "setting" && shown.iSet -> "Pick a word for $partner"
            shown.status == "setting" -> "$partner is picking a word"
            myTurn -> "Your turn to guess"
            else -> "$partner is guessing"
        }
        val detail = when {
            shown.over -> "${shown.scoreMe} – ${shown.scoreThem}  (you – $partner). Tap for the next round."
            myTurn && shown.status == "setting" -> "Word Duel with $partner"
            myTurn -> "Word Duel. ${shown.triesLeft} tries left."
            else -> "Word Duel. They'll be told it's their move."
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(shape)
                .background(LockInColors.Slate)
                .border(1.5.dp, if (myTurn) LockInColors.Lime else LockInColors.Graphite, shape)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = "$title. $detail. Open the game." },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniWord(shown, Modifier.size(46.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = LockInText.Label.copy(color = if (myTurn) LockInColors.Lime else LockInColors.Snow, fontSize = 15.sp), maxLines = 1)
                Text(detail, style = LockInText.Small.copy(color = LockInColors.Mist), maxLines = 1)
            }
            Text(
                if (shown.over) "Next" else if (myTurn) "Play" else "Open",
                style = LockInText.Label.copy(color = if (myTurn) LockInColors.OnLime else LockInColors.Snow, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (myTurn) LockInColors.Lime else LockInColors.Graphite)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Six rows of five little squares, coloured like the guesses. */
@Composable
private fun MiniWord(g: WdGame, modifier: Modifier) {
    val empty = LockInColors.Ink.copy(alpha = 0.6f)
    Canvas(modifier) {
        val gap = 1.5.dp.toPx()
        val cw = (size.width - gap * 4) / 5f
        val ch = (size.height - gap * 5) / 6f
        for (r in 0 until 6) for (c in 0 until 5) {
            val mark = g.guesses.getOrNull(r)?.marks?.getOrNull(c)
            val color = when (mark) {
                'g' -> LockInColors.Lime
                'y' -> androidx.compose.ui.graphics.Color(0xFFE6B53A)
                'b' -> LockInColors.tone(0.22f)
                else -> empty
            }
            drawRoundRect(color, Offset(c * (cw + gap), r * (ch + gap)), Size(cw, ch), CornerRadius(2.dp.toPx()))
        }
    }
}

/** Which game to play. */
@Composable
internal fun GamePicker(onTicTacToe: () -> Unit, onWordDuel: () -> Unit, onLudo: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Text("Play with your partner", style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
        GameChoice("Ludo", "Race your four tokens home. Real dice, captures, and every roll live.", onLudo) {
            Canvas(Modifier.size(34.dp)) {
                val cols = com.godbless.lockin.ludo.LudoPalette.base
                val h = size.width / 2f
                for (i in 0..3) {
                    val x = if (i == 1 || i == 2) h else 0f
                    val y = if (i >= 2) h else 0f
                    drawRoundRect(cols[i], Offset(x + 1f, y + 1f), Size(h - 2f, h - 2f), CornerRadius(4.dp.toPx()))
                }
                drawCircle(androidx.compose.ui.graphics.Color.White, h * 0.42f, Offset(h, h))
                drawCircle(androidx.compose.ui.graphics.Color(0xFF1C1E24), h * 0.09f, Offset(h, h))
            }
        }
        GameChoice("Word Duel", "Set a secret word, or guess theirs. Watch each other type, live.", onWordDuel) {
            Canvas(Modifier.size(34.dp)) {
                val gap = 2.dp.toPx()
                val cw = (size.width - gap * 4) / 5f
                val cols = listOf(LockInColors.Lime, androidx.compose.ui.graphics.Color(0xFFE6B53A), LockInColors.tone(0.22f), LockInColors.Lime, LockInColors.tone(0.22f))
                for (i in 0 until 5) drawRoundRect(cols[i], Offset(i * (cw + gap), size.height * 0.34f), Size(cw, size.height * 0.34f), CornerRadius(2.dp.toPx()))
            }
        }
        GameChoice("Tic-tac-toe", "Three marks each, then slide them. See their hand move.", onTicTacToe) {
            Canvas(Modifier.size(34.dp)) {
                val w = 2.dp.toPx()
                val a = size.width / 3f
                drawLine(LockInColors.Frost, Offset(a, 0f), Offset(a, size.height), w, StrokeCap.Round)
                drawLine(LockInColors.Frost, Offset(a * 2, 0f), Offset(a * 2, size.height), w, StrokeCap.Round)
                drawLine(LockInColors.Frost, Offset(0f, a), Offset(size.width, a), w, StrokeCap.Round)
                drawLine(LockInColors.Frost, Offset(0f, a * 2), Offset(size.width, a * 2), w, StrokeCap.Round)
                drawCircle(LockInColors.Lime, a * 0.28f, Offset(a * 1.5f, a * 1.5f), style = Stroke(w))
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun GameChoice(title: String, detail: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Graphite)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = LockInText.Label.copy(color = LockInColors.Snow, fontSize = 16.sp))
            Text(detail, style = LockInText.Small.copy(color = LockInColors.Mist))
        }
    }
}
