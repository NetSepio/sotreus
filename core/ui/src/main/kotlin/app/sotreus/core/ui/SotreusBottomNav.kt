package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme

data class SotreusNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** Four-tab primary navigation. The active tab gets the amber pill (mocks 03, 04, 08, 10, 11, G1, S1). */
@Composable
fun SotreusBottomNav(items: List<SotreusNavItem>, modifier: Modifier = Modifier) {
    val colors = SotreusTheme.colors
    val spacing = SotreusTheme.spacing
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(thickness = 1.dp, color = colors.lineNav)
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = spacing.m, end = spacing.m, top = spacing.m, bottom = 14.dp)
                .selectableGroup(),
        ) {
            items.forEach { item -> NavItem(item, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NavItem(item: SotreusNavItem, modifier: Modifier) {
    val colors = SotreusTheme.colors
    val sizes = SotreusTheme.sizes
    Column(
        modifier
            .heightIn(min = sizes.navItem)
            .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(width = sizes.navPillWidth, height = sizes.navPillHeight)
                .then(
                    if (item.selected) {
                        Modifier.background(colors.accentNavPill, SotreusTheme.shapes.pill)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.selected) colors.accent else colors.textMuted,
                modifier = Modifier.size(sizes.iconSize),
            )
        }
        Text(
            text = item.label,
            style = SotreusTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = if (item.selected) colors.text else colors.textMuted,
        )
    }
}

@Preview(widthDp = 390)
@Composable
private fun SotreusBottomNavPreview() {
    SotreusTheme {
        SotreusBottomNav(
            listOf(
                SotreusNavItem("Now", SotreusIcons.Now, selected = true) {},
                SotreusNavItem("History", SotreusIcons.History, selected = false) {},
                SotreusNavItem("Journey", SotreusIcons.Journey, selected = false) {},
                SotreusNavItem("Settings", SotreusIcons.Settings, selected = false) {},
            ),
        )
    }
}
