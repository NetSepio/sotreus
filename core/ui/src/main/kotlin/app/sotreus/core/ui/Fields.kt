package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Pill search field on `surface` (screen 08). */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, label: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).background(c.surface, SotreusTheme.shapes.pill).border(1.dp, c.lineStrong, SotreusTheme.shapes.pill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l),
    ) {
        Icon(SotreusIcons.Search, contentDescription = null, tint = c.textDim, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = SotreusTheme.typography.bodyS.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.textDim)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = SotreusTheme.typography.bodyS.copy(color = c.text, fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

/** Multi-line note area (screen 06): local only. */
@Composable
fun NoteField(value: String, onValueChange: (String) -> Unit, placeholder: String, label: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        MonoLabel(label)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).background(c.surface, SotreusTheme.shapes.listCard).border(1.dp, c.lineStrong, SotreusTheme.shapes.listCard)
                .padding(horizontal = 14.dp, vertical = SotreusTheme.spacing.xl),
        ) {
            if (value.isEmpty()) Text(placeholder, style = SotreusTheme.typography.bodyS.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.textDim)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = SotreusTheme.typography.bodyS.copy(color = c.text, fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

/** Pill single-line input (sign-in email, profile name). */
@Composable
fun PillField(value: String, onValueChange: (String) -> Unit, placeholder: String, label: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        MonoLabel(label)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).background(c.surface, SotreusTheme.shapes.pill).border(1.dp, c.lineStrong, SotreusTheme.shapes.pill)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, style = SotreusTheme.typography.body, color = c.textDim)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = SotreusTheme.typography.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

/** Confirm dialog for destructive actions. [body] must state exactly what is removed. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    val c = SotreusTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        titleContentColor = c.text,
        textContentColor = c.textSoft,
        shape = SotreusTheme.shapes.featureCard,
        title = { Text(title, style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.8f)) },
        text = { Text(body, style = SotreusTheme.typography.bodyS) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch)) {
                Text(confirm, style = SotreusTheme.typography.button, color = if (destructive) c.destructive else c.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch)) {
                Text(dismiss, style = SotreusTheme.typography.button, color = c.textMuted)
            }
        },
    )
}

/** Single text input dialog (rename, new place, add note). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirm: String,
    dismiss: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
) {
    val c = SotreusTheme.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        titleContentColor = c.text,
        shape = SotreusTheme.shapes.featureCard,
        title = { Text(title, style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.8f)) },
        text = {
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (multiline) 96.dp else 48.dp).background(c.surface, SotreusTheme.shapes.listCard)
                    .border(1.dp, c.lineStrong, SotreusTheme.shapes.listCard).padding(14.dp),
            ) {
                if (text.isEmpty()) Text(placeholder, style = SotreusTheme.typography.body, color = c.textDim)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    textStyle = SotreusTheme.typography.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch)) {
                Text(confirm, style = SotreusTheme.typography.button, color = if (text.isNotBlank()) c.accent else c.textDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch)) {
                Text(dismiss, style = SotreusTheme.typography.button, color = c.textMuted)
            }
        },
    )
}
