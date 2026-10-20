package com.nexus.downloader.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nexus.downloader.data.model.OptionSpec
import com.nexus.downloader.data.model.OptionType

/**
 * アドオンのオプション 1 項目を描画する。
 * SELECT はドロップダウン、TEXT/PASSWORD は入力欄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionField(
    spec: OptionSpec,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val supporting: (@Composable () -> Unit)? = if (spec.content.isNotBlank()) {
        { Text(spec.content) }
    } else {
        null
    }

    when (spec.type) {
        OptionType.SELECT -> {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(spec.label) },
                    supportingText = supporting,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    spec.choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                onValueChange(choice)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        OptionType.TEXT, OptionType.PASSWORD -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(spec.label) },
                supportingText = supporting,
                singleLine = true,
                visualTransformation = if (spec.type == OptionType.PASSWORD) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}
