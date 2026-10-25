package com.vibe.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

/**
 * Single-line TV text field: type or paste, then Done saves. Secrets are masked and the field clears after
 * saving; plain fields start from [initial].
 */
@Composable
fun TvTextField(
    hint: String,
    secret: Boolean,
    modifier: Modifier = Modifier,
    initial: String = "",
    onSave: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = draft,
        onValueChange = { draft = it.copy(text = it.text.trim()) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = GoogleSans),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            if (secret) {
                if (draft.text.isNotBlank()) { onSave(draft.text); draft = TextFieldValue("") }
            } else {
                onSave(draft.text)
            }
        }),
        modifier = modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused }.dpadExit(draft, focusManager)
            .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.text.isEmpty()) Text(hint, color = TextSecondary, fontSize = 15.sp)
                inner()
            }
        },
    )
}

/** TV text fields swallow the D-pad; let arrows leave the field at its edges so other buttons stay reachable. */
fun Modifier.dpadExit(value: TextFieldValue, focusManager: FocusManager): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val sel = value.selection
    when (e.key) {
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
        Key.DirectionRight -> sel.collapsed && sel.end >= value.text.length && focusManager.moveFocus(FocusDirection.Right)
        Key.DirectionLeft -> sel.collapsed && sel.start == 0 && focusManager.moveFocus(FocusDirection.Left)
        else -> false
    }
}
