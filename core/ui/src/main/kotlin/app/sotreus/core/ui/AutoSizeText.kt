package app.sotreus.core.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of text that steps its size down from [style]'s size to [minSize] so a long name (a
 * place, an entity) fits instead of wrapping. Only past [minSize] does it end with an ellipsis.
 */
@Composable
fun AutoSizeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 12.sp,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, lineHeight = TextUnit.Unspecified),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}
