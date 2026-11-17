package com.godbless.lockin.home

import com.godbless.lockin.ui.twoPane
import com.godbless.lockin.ui.useRail
import com.godbless.lockin.ui.readable
import com.godbless.lockin.ui.Widths
import com.godbless.lockin.ui.isTablet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText
import com.godbless.lockin.ui.rememberEntrance
import com.godbless.lockin.ui.rise

/** Space between the screen edge and the cards on every page, and around the tab bar. */
internal val PageGutter = 10.dp

/**
 * The frame every tab page sits in: a scrolling column under the status bar with room at the bottom
 * for the floating tab bar. [content] gets the page's entrance, so rows can slide up one after another.
 */
@Composable
internal fun PageScaffold(
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
    entranceMillis: Int = 900,
    /** A tab page laid out in [TwoColumns]: on a big screen it gets the full wide width. */
    wide: Boolean = false,
    /** Names this page so its entrance plays once per app run, not on every visit (tab pages pass one). */
    key: String? = null,
    content: @Composable ColumnScope.(State<Float>) -> Unit,
) {
    val entrance = rememberEntrance(entranceMillis, key)
    val rail = useRail()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            // On a tablet the page is a centred column, or two columns side by side when there's room.
            .readable(if (wide && twoPane()) Widths.Wide else Widths.Page)
            .padding(horizontal = if (isTablet()) PageGutter * 3 else PageGutter),
    ) {
        Spacer(Modifier.height(if (isTablet()) 32.dp else 14.dp))
        content(entrance)
        // Room for the floating tab bar; with the rail on the side there's none to leave.
        Spacer(Modifier.height(if (rail) 40.dp else 130.dp))
    }
}

/** A page heading with one line underneath. */
@Composable
internal fun PageTitle(title: String, subtitle: String, entrance: State<Float>, modifier: Modifier = Modifier) {
    Column(modifier.rise(entrance, 0).padding(horizontal = 4.dp)) {
        Text(title, style = LockInText.Headline.copy(fontSize = 38.sp))
        Text(subtitle, style = LockInText.Sub.copy(color = LockInColors.Silver))
    }
}



/**
 * Two columns side by side on a big screen; on a phone, [left] then [right] one under the other, exactly as
 * the page always was. Each column stacks its own cards, so a tall card on one side doesn't stretch the other.
 */
@Composable
internal fun TwoColumns(
    modifier: Modifier = Modifier,
    leftWeight: Float = 1.15f,
    gap: androidx.compose.ui.unit.Dp = 20.dp,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (twoPane()) {
        androidx.compose.foundation.layout.Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            Column(Modifier.weight(leftWeight)) { left() }
            Column(Modifier.weight(1f)) { right() }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            left()
            right()
        }
    }
}
