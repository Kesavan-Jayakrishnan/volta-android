package dev.irshadm.volta.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Boxes for a short numeric code, drawn as dots. A hidden text field takes the input, so the number pad, paste and
 * autofill all work as usual. The next box to fill is outlined in gold, or every box in red when [isError].
 */
@Composable
fun VoltaPinField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 4,
    isError: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = true,
    onDone: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(length)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = modifier.focusRequester(focus).semantics {
            contentDescription = "$length-digit PIN"
            password()
        },
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(length) { i ->
                    val shape = RoundedCornerShape(12.dp)
                    val border = when {
                        isError -> VoltaColors.Red
                        enabled && i == value.length -> VoltaColors.Gold
                        else -> VoltaColors.BorderStrong
                    }
                    Box(Modifier.size(52.dp, 60.dp).clip(shape).background(VoltaColors.Surface).border(1.dp, border, shape), contentAlignment = Alignment.Center) {
                        if (i < value.length) Box(Modifier.size(12.dp).clip(CircleShape).background(VoltaColors.Text))
                    }
                }
            }
        },
    )
}

@Preview(backgroundColor = 0xFF0B0D10, showBackground = true)
@Composable
private fun VoltaPinFieldPreview() = VoltaTheme {
    VoltaPinField("12", {}, Modifier.padding(16.dp), autoFocus = false)
}
