package com.godbless.lockin.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Lets the phone's password manager (Google, 1Password, Bitwarden...) offer to fill a field.
 * This is the only file that uses Compose's autofill API; if a future Compose version changes it,
 * this is the one place to update.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(types: List<AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val latest: State<(String) -> Unit> = rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = types, onFill = { latest.value(it) }) }
    // Register while the field is on screen, and forget it when the field leaves, so closed screens don't pile up.
    DisposableEffect(node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.run {
                if (state.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node)
            }
        }
}
