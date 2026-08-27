package com.dot.gallery.feature_node.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalGridApi
import androidx.compose.foundation.layout.Grid
import androidx.compose.foundation.layout.GridTrackSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dot.gallery.core.Position
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.core.presentation.components.ModalSheet
import com.dot.gallery.feature_node.presentation.common.components.OptionPosition.ALONE
import com.dot.gallery.feature_node.presentation.common.components.OptionPosition.BOTTOM
import com.dot.gallery.feature_node.presentation.common.components.OptionPosition.MIDDLE
import com.dot.gallery.feature_node.presentation.common.components.OptionPosition.TOP
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.feature_node.presentation.settings.components.SettingsItem
import com.dot.gallery.feature_node.presentation.util.AppBottomSheetState

@Composable
fun OptionSheet(
    state: AppBottomSheetState,
    onDismiss: (() -> Unit)? = null,
    headerContent: @Composable (ColumnScope.() -> Unit)? = null,
    style: OptionLayoutStyle = OptionLayoutStyle.Column,
    vararg optionList: SnapshotStateList<OptionItem>
) {
    ModalSheet(
        sheetState = state,
        onDismissRequest = { onDismiss?.invoke() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        content = {
            headerContent?.invoke(this)
            optionList.forEach { list ->
                OptionLayout(
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    optionList = list
                )
            }
        }
    )
}

fun LazyListScope.SettingsOptionLayout(
    modifier: Modifier = Modifier,
    optionList: List<SettingsEntity>,
    slimLayout: Boolean = false,
    swipeToDismiss: Boolean = false,
    onDismiss: ((SettingsEntity) -> Unit)? = null
) {
    itemsIndexed(
        items = optionList,
        key = { _, item -> item.toString() }
    ) { index, item ->
        val position: Position = remember(index, item) {
            when (index) {
                0 -> {
                    if (optionList.size == 1) Position.Alone
                    else Position.Top
                }

                optionList.lastIndex -> {
                    if (optionList[(index - 1).coerceAtLeast(0)] is SettingsEntity.Header) {
                        Position.Alone
                    } else Position.Bottom
                }

                else -> {
                    val previous = optionList[(index - 1).coerceAtLeast(0)]
                    val next = optionList[(index + 1).coerceAtMost(optionList.lastIndex)]
                    if (previous is SettingsEntity.Header && next is SettingsEntity.Header) {
                        Position.Alone
                    } else if (previous is SettingsEntity.Header) {
                        Position.Top
                    } else if (next is SettingsEntity.Header) {
                        Position.Bottom
                    } else {
                        Position.Middle
                    }
                }
            }
        }
        val newItem = remember(item) {
            when (item) {
                is SettingsEntity.Preference -> item.copy(
                    screenPosition = position,
                )

                is SettingsEntity.SwitchPreference -> item.copy(
                    screenPosition = position
                )

                is SettingsEntity.SeekPreference -> item.copy(
                    screenPosition = position
                )

                else -> item
            }
        }
        if (swipeToDismiss && newItem !is SettingsEntity.Header) {
            val swipeToDismissBoxState = rememberSwipeToDismissBoxState(
                SwipeToDismissBoxValue.Settled,
                SwipeToDismissBoxDefaults.positionalThreshold
            )

            SwipeToDismissBox(
                state = swipeToDismissBoxState,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(),
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    val shape by rememberedDerivedState(position) {
                        when (position) {
                            Position.Alone -> RoundedCornerShape(24.dp)
                            Position.Bottom -> RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 8.dp,
                                bottomStart = 24.dp,
                                bottomEnd = 24.dp
                            )

                            Position.Middle -> RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 8.dp,
                                bottomStart = 8.dp,
                                bottomEnd = 8.dp
                            )

                            Position.Top -> RoundedCornerShape(
                                topStart = 24.dp,
                                topEnd = 24.dp,
                                bottomStart = 8.dp,
                                bottomEnd = 8.dp
                            )
                        }
                    }
                    val paddingModifier by rememberedDerivedState(position) {
                        when (position) {
                            Position.Alone -> Modifier.padding(bottom = 16.dp)
                            Position.Bottom -> Modifier.padding(top = 1.dp, bottom = 16.dp)
                            Position.Middle -> Modifier.padding(vertical = 1.dp)
                            Position.Top -> Modifier.padding(bottom = 1.dp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .then(paddingModifier)
                            .fillMaxSize()
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = shape
                            ),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                },
                onDismiss = {
                    if (it == SwipeToDismissBoxValue.EndToStart) {
                        onDismiss?.invoke(newItem)
                    }
                },
                content = {
                    SettingsItem(
                        modifier = modifier.animateItem(),
                        item = newItem,
                        slimLayout = slimLayout,
                        tintIcon = newItem.iconUri == null
                    )
                }
            )
        } else {
            SettingsItem(
                modifier = modifier.animateItem(),
                item = newItem,
                slimLayout = slimLayout,
                tintIcon = newItem.iconUri == null
            )
        }
    }
}


enum class OptionLayoutStyle {
    Grid,
    Column
}

private val GridItemShape = RoundedCornerShape(20.dp)

@Composable
fun OptionLayout(
    modifier: Modifier = Modifier,
    optionList: SnapshotStateList<OptionItem>,
    style: OptionLayoutStyle = OptionLayoutStyle.Column
) {
    when (style) {
        OptionLayoutStyle.Grid -> OptionGridLayout(modifier, optionList)
        OptionLayoutStyle.Column -> OptionColumnLayout(modifier, optionList)
    }
}

@OptIn(ExperimentalGridApi::class)
@Composable
private fun OptionGridLayout(
    modifier: Modifier = Modifier,
    optionList: SnapshotStateList<OptionItem>
) {
    Grid(
        config = {
            repeat(2) { column(GridTrackSize.MinMax(min = 0.dp, max = 1.fr)) }
            columnGap(8.dp)
            rowGap(8.dp)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        val isOdd = optionList.size % 2 != 0
        optionList.forEachIndexed { index, item ->
            val isLastOdd = isOdd && index == optionList.lastIndex
            OptionGridItem(
                item = item,
                modifier = if (isLastOdd) Modifier.gridItem(columnSpan = 2) else Modifier
            )
        }
    }
}

@Composable
private fun OptionColumnLayout(
    modifier: Modifier = Modifier,
    optionList: SnapshotStateList<OptionItem>
) {
    Column(
        modifier = modifier
            .clip(OptionShape.Alone)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = OptionShape.Alone
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        optionList.forEachIndexed { index, item ->
            val position: OptionPosition = remember(index, item) {
                when (index) {
                    0 -> {
                        if (optionList.size == 1) ALONE
                        else TOP
                    }

                    optionList.lastIndex -> BOTTOM
                    else -> MIDDLE
                }
            }
            val summary: (@Composable () -> Unit)? = if (item.summary.isNullOrBlank()) null else {
                {
                    Text(text = item.summary)
                }
            }
            OptionButton(
                modifier = Modifier.fillMaxWidth(),
                icon = item.icon,
                textContainer = {
                    Text(text = item.text)
                },
                summaryContainer = summary,
                enabled = item.enabled,
                containerColor = item.containerColor
                    ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = item.contentColor
                    ?: MaterialTheme.colorScheme.onSurface,
                position = position,
                onClick = {
                    item.onClick(item.summary.toString())
                }
            )
        }
    }
}

@Composable
private fun OptionGridItem(
    item: OptionItem,
    modifier: Modifier = Modifier
) {
    val containerColor = item.containerColor
        ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = item.contentColor
        ?: MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .defaultMinSize(minHeight = 80.dp)
            .background(containerColor, GridItemShape)
            .clip(GridItemShape)
            .clickable(enabled = item.enabled) { item.onClick(item.summary.toString()) }
            .alpha(if (item.enabled) 1f else 0.4f)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!item.summary.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.summary,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun OptionButton(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null,
    textContainer: @Composable () -> Unit,
    summaryContainer: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    position: OptionPosition = ALONE,
    onClick: () -> Unit
) {
    val mod = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .background(
            color = containerColor,
            shape = position.shape()
        )
        .clip(position.shape())
        .clickable(
            enabled = enabled,
            onClick = onClick
        )
        .alpha(if (enabled) 1f else 0.4f)
        .padding(16.dp)
        .padding(vertical = 4.dp)
    Row(
        modifier = mod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.4f)
                    .padding(start = 4.dp, end = 12.dp)
            )
        }
        if (summaryContainer != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ProvideTextStyle(
                    value = MaterialTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    textContainer()
                }
                ProvideTextStyle(
                    value = MaterialTheme.typography.labelMedium.copy(
                        color = contentColor,
                        fontWeight = FontWeight.Normal
                    )
                ) {
                    summaryContainer()
                }
            }
        } else {
            Box(
                contentAlignment = Alignment.Center
            ) {
                ProvideTextStyle(
                    value = MaterialTheme.typography.bodyMedium.copy(color = contentColor)
                ) {
                    textContainer()
                }
            }
        }
    }
}

data class OptionItem(
    val icon: ImageVector? = null,
    val text: String,
    val summary: String? = null,
    val onClick: (summary: String) -> Unit,
    val enabled: Boolean = true,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
)

object OptionShape {

    val Top = RoundedCornerShape(
        topEnd = 24.dp,
        topStart = 24.dp,
        bottomEnd = 2.dp,
        bottomStart = 2.dp
    )

    val Middle = RoundedCornerShape(
        topEnd = 2.dp,
        topStart = 2.dp,
        bottomEnd = 2.dp,
        bottomStart = 2.dp
    )

    val Bottom = RoundedCornerShape(
        topEnd = 2.dp,
        topStart = 2.dp,
        bottomEnd = 24.dp,
        bottomStart = 24.dp
    )

    val Alone = RoundedCornerShape(
        topEnd = 24.dp,
        topStart = 24.dp,
        bottomEnd = 24.dp,
        bottomStart = 24.dp
    )
}

enum class OptionPosition {
    TOP, MIDDLE, BOTTOM, ALONE
}

fun OptionPosition.shape(): RoundedCornerShape = when (this) {
    TOP -> OptionShape.Top
    MIDDLE -> OptionShape.Middle
    BOTTOM -> OptionShape.Bottom
    ALONE -> OptionShape.Alone
}

@Preview(showBackground = true)
@Composable
private fun OptionLayoutAlonePreview() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            val list = remember {
                mutableStateListOf(
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Alone Option",
                        onClick = {}
                    )
                )
            }
            OptionLayout(optionList = list)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OptionLayoutTwoItemsPreview() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            val list = remember {
                mutableStateListOf(
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "First Option",
                        onClick = {}
                    ),
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Second Option",
                        onClick = {}
                    )
                )
            }
            OptionLayout(optionList = list)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OptionLayoutGridPreview() {
    MaterialTheme {
        Box(Modifier.padding(16.dp)) {
            val customContainerColor = MaterialTheme.colorScheme.primaryContainer
            val customContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            val list = remember(customContainerColor, customContentColor) {
                mutableStateListOf(
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Delete",
                        onClick = {}
                    ),
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Pin",
                        onClick = {}
                    ),
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Change cover",
                        onClick = {}
                    ),
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Lock album",
                        onClick = {}
                    ),
                    OptionItem(
                        icon = Icons.Outlined.Delete,
                        text = "Odd Item",
                        containerColor = customContainerColor,
                        contentColor = customContentColor,
                        onClick = {}
                    )
                )
            }
            OptionLayout(optionList = list)
        }
    }
}
