/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.dot.gallery.core.presentation.components.SetupButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dot.gallery.R
import com.dot.gallery.feature_node.presentation.util.AppBottomSheetState
import kotlinx.coroutines.launch

@Composable
fun CollectionSheet(
    sheetState: AppBottomSheetState,
    mode: String, // "create" or "rename"
    initialName: String = "",
    onCreateCollection: (String) -> Unit = {},
    onRenameCollection: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var collectionName by remember(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }

    ModalSheet(
        sheetState = sheetState,
        title = when (mode) {
            "rename" -> stringResource(R.string.rename_collection)
            else -> stringResource(R.string.new_collection)
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        content = {
            OutlinedTextField(
                value = collectionName,
                onValueChange = { collectionName = it },
                label = { Text(stringResource(R.string.collection_name)) },
                placeholder = { Text(stringResource(R.string.collection_name_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .imePadding(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (collectionName.isNotBlank()) {
                            scope.launch {
                                sheetState.hide()
                                when (mode) {
                                    "rename" -> onRenameCollection(collectionName.trim())
                                    else -> onCreateCollection(collectionName.trim())
                                }
                            }
                        }
                    }
                )
            )
            Spacer(Modifier.height(16.dp))
            SetupButton(
                onClick = {
                    if (collectionName.isNotBlank()) {
                        scope.launch {
                            sheetState.hide()
                            when (mode) {
                                "rename" -> onRenameCollection(collectionName.trim())
                                else -> onCreateCollection(collectionName.trim())
                            }
                        }
                    }
                },
                enabled = collectionName.isNotBlank(),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                text = when (mode) {
                    "rename" -> stringResource(R.string.rename_collection)
                    else -> stringResource(R.string.create_collection)
                }
            )
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }
    )
}

@Composable
fun DeleteCollectionSheet(
    sheetState: AppBottomSheetState,
    onConfirmDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()

    ModalSheet(
        sheetState = sheetState,
        title = stringResource(R.string.delete_collection),
        subtitle = stringResource(R.string.delete_collection_confirm),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        content = {
            SetupButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onConfirmDelete()
                    }
                },
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                text = stringResource(R.string.delete_collection)
            )
        }
    )
}
