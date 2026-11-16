package com.godbless.lockin.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablets and big screens.
 *
 * LockIn is laid out for a phone held upright. On a tablet, cards stretched across the whole screen read badly
 * (lines too long, buttons a hand-span wide), so the content sits in a centred column of a comfortable width,
 * with the dark background running edge to edge behind it. Full-screen scenes (the mountain, the lock cover)
 * still fill the screen.
 */
object Widths {
    /** Pages: Home, Study, Partner, You and everything that opens from them. */
    val Page = 620.dp

    /** Sheets and pickers that rise from the bottom. */
    val Sheet = 560.dp

    /** The chat: a little wider, so bubbles don't feel cramped. */
    val Chat = 720.dp

    /** Reading a PDF, where more width is welcome. */
    val Reader = 900.dp

    /** The bottom tab bar. */
    val TabBar = 380.dp

    /** Two-column pages on a big screen: wide, but not so wide the columns drift apart. */
    val Wide = 1160.dp

    /** The tab rail down the left side on a big screen. */
    val Rail = 92.dp
    /** A chat opened beside the page on a big screen. */
    val ChatPane = 460.dp
}

/** Wide enough for the tabs to move to a rail down the left (tablets, and big phones turned sideways). */
@Composable
@ReadOnlyComposable
fun useRail(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * How much of the page's width a chat panel on the right is covering right now (0 when none is open). Pages ask for
 * two columns only if they still fit once it is taken off, so a page flows into one column beside an open chat
 * instead of sitting half hidden under it.
 */
val LocalChatInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Wide enough (with the rail) for pages to split into two columns. */
@Composable
@ReadOnlyComposable
fun twoPane(): Boolean = LocalConfiguration.current.screenWidthDp - LocalChatInset.current.value >= 840

/** True on tablets and other large screens (smallest side at least 600 dp, whichever way it's held). */
@Composable
@ReadOnlyComposable
fun isTablet(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= 600

/** True when the screen is wide right now: a tablet, or a big phone or foldable turned sideways. */
@Composable
@ReadOnlyComposable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Fills the width up to [max], centred. On a phone it's simply full width; on a tablet the content becomes a
 * centred column. Put it after any background and scroll modifiers so those still reach the edges.
 */
fun Modifier.readable(max: Dp = Widths.Page): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
