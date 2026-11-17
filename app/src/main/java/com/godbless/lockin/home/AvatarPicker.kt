package com.godbless.lockin.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import kotlinx.coroutines.delay

/** All, Male, Female: a segmented control whose highlight slides to the choice. */
@Composable
internal fun LookSegments(look: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("" to "All", "m" to "Male", "f" to "Female")
    val index = items.indexOfFirst { it.first == look }.coerceAtLeast(0)
    val slide by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.8f, stiffness = 500f), label = "lookSlide")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(LockInColors.Graphite),
    ) {
        val segment = maxWidth / items.size
        Box(
            Modifier
                .offset(x = segment * slide)
                .width(segment)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(LockInColors.Lime),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEach { (id, label) ->
                val on = id == look
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(23.dp))
                        .clickable(role = Role.RadioButton) { onChange(id) }
                        .semantics { contentDescription = label; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = LockInText.Label.copy(fontSize = 15.sp, color = if (on) LockInColors.Ink else LockInColors.Silver))
                }
            }
        }
    }
}

/** One style in the row: a round preview with its name, lifting slightly and ringing in lime when chosen. */
@Composable
internal fun StyleCard(style: String, label: String, seed: String, look: String, chosen: Boolean, onClick: () -> Unit) {
    val lift by animateFloatAsState(if (chosen) 1.08f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "styleLift")
    val ring by animateFloatAsState(if (chosen) 1f else 0f, spring(stiffness = 500f), label = "styleRing")
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "$label style"; selected = chosen },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .graphicsLayer { scaleX = lift; scaleY = lift }
                .clip(CircleShape)
                .background(LockInColors.Graphite)
                .border(3.dp, LockInColors.Lime.copy(alpha = ring), CircleShape)
                .padding(3.dp)
                .clip(CircleShape),
        ) { DiceImage(style, seed, look, Modifier.fillMaxSize()) }
        Text(
            label,
            style = LockInText.Small.copy(
                fontSize = 12.sp,
                color = if (chosen) LockInColors.Lime else LockInColors.Silver,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** One option in the "pick a look" grid. Pops in a beat after its neighbour each time the grid is shuffled. */
@Composable
internal fun LookCell(style: String, seed: String, look: String, index: Int, chosen: Boolean, onClick: () -> Unit) {
    val pop = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) {
        delay(index * 45L)
        pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 350f))
    }
    val lift by animateFloatAsState(if (chosen) 1.07f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "cellLift")
    val ring by animateFloatAsState(if (chosen) 1f else 0f, spring(stiffness = 500f), label = "cellRing")
    Box(
        Modifier
            .size(72.dp)
            .graphicsLayer {
                val s = pop.value * lift
                scaleX = s
                scaleY = s
                alpha = pop.value.coerceIn(0f, 1f)
            }
            .clip(CircleShape)
            .background(LockInColors.Graphite)
            .border(3.dp, LockInColors.Lime.copy(alpha = ring), CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Avatar option ${index + 1}"; selected = chosen },
    ) { DiceImage(style, seed, look, Modifier.fillMaxSize()) }
}
