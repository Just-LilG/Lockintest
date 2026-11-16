package com.godbless.lockin.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godbless.lockin.ui.ButtonKind
import com.godbless.lockin.ui.LockInButton
import com.godbless.lockin.ui.LockInColors
import com.godbless.lockin.ui.LockInText

/**
 * A sheet for writing one flashcard: the question, the answer, and where in which book it came from.
 * It stays open after saving so several cards can be made in a row.
 */
@Composable
internal fun CardEditor(
    store: StudyStore,
    startBook: String,
    startPage: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** A card to change instead of making a new one. */
    edit: Card? = null,
) {
    // Opening the editor on a different card starts from that card's words.
    var question by rememberSaveable(edit?.id) { mutableStateOf(edit?.question.orEmpty()) }
    var answer by rememberSaveable(edit?.id) { mutableStateOf(edit?.answer.orEmpty()) }
    var page by rememberSaveable(edit?.id) { mutableStateOf(edit?.page?.takeIf { it > 0 }?.toString() ?: if (startPage > 0) startPage.toString() else "") }
    var book by rememberSaveable(edit?.id) { mutableStateOf(edit?.book ?: startBook) }
    var saved by rememberSaveable { mutableStateOf(0) }
    // A picture from the page (a diagram, a table): its file name, or "".
    var image by rememberSaveable(edit?.id) { mutableStateOf(edit?.image.orEmpty()) }
    var cropping by rememberSaveable { mutableStateOf(false) }
    val pageNo = page.toIntOrNull() ?: 0
    // A picture taken here but never saved with a card is thrown away.
    fun dropUnsaved() { if (image.isNotEmpty() && image != edit?.image) store.imageFile(image).delete() }

    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LockInColors.Slate)
            .border(1.dp, LockInColors.Graphite, shape)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (edit != null) "Edit card" else "New card", style = LockInText.Title.copy(fontSize = 22.sp, lineHeight = 28.sp), modifier = Modifier.weight(1f))
            if (edit == null && saved > 0) {
                Text(
                    if (saved == 1) "1 saved" else "$saved saved",
                    style = LockInText.Label.copy(color = LockInColors.Lime, fontSize = 13.sp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StudyField(question, { question = it }, "Question")
        Spacer(Modifier.height(10.dp))
        StudyField(answer, { answer = it }, "Answer")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            StudyField(page, { page = it.filter(Char::isDigit).take(4) }, "Page", Modifier.width(96.dp), number = true)
            if (store.books.isNotEmpty()) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    store.books.forEach { b ->
                        val on = b.id == book
                        Box(
                            Modifier
                                .height(38.dp)
                                .clip(CircleShape)
                                .background(if (on) LockInColors.Lime else LockInColors.Slate)
                                .border(1.5.dp, if (on) LockInColors.Lime else LockInColors.Rim, CircleShape)
                                .clickable(role = Role.Button) { book = if (on) "" else b.id }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                prettyTitle(b.title),
                                style = LockInText.Label.copy(color = if (on) LockInColors.OnLime else LockInColors.Snow, fontSize = 13.sp),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (image.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardImage(store, image, Modifier.weight(1f).heightIn(max = 120.dp))
                Text(
                    "Remove",
                    style = LockInText.Label.copy(color = LockInColors.Flare, fontSize = 13.sp),
                    modifier = Modifier.padding(start = 8.dp).clip(CircleShape).clickable(role = Role.Button) { dropUnsaved(); image = "" }.padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        } else {
            val can = book.isNotEmpty() && store.book(book) != null
            Text(
                when {
                    !can -> "Pick a book to add a picture from it"
                    pageNo > 0 -> "＋ Add a picture from page $pageNo"
                    else -> "＋ Add a picture from the book"
                },
                style = LockInText.Label.copy(color = if (can) LockInColors.Lime else LockInColors.Mist, fontSize = 14.sp),
                modifier = Modifier.clip(CircleShape).clickable(enabled = can, role = Role.Button) { cropping = true }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        if (cropping) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { cropping = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                PageCropper(
                    store = store,
                    bookId = book,
                    page = if (pageNo > 0) pageNo else (store.book(book)?.lastPage ?: 0) + 1,
                    onCrop = { name -> dropUnsaved(); image = name; cropping = false },
                    onCancel = { cropping = false },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockInButton(if (edit != null) "Cancel" else "Done", onClick = { dropUnsaved(); image = edit?.image.orEmpty(); onClose() }, modifier = Modifier.weight(1f), kind = ButtonKind.Outline)
            LockInButton(
                if (edit != null) "Save changes" else "Save card",
                onClick = {
                    if (edit != null) {
                        store.updateCard(edit.id, book, question, answer, page.toIntOrNull() ?: 0, image)
                        onClose()
                    } else {
                        store.addCard(book, question, answer, page.toIntOrNull() ?: 0, image)
                        question = ""
                        answer = ""
                        image = ""
                        saved += 1
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = question.isNotBlank() && answer.isNotBlank(),
            )
        }
    }
}
