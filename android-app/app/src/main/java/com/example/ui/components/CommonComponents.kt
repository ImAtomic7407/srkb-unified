package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    enabled: Boolean = true,
    // Numeric fields (day counts, sort positions) should raise the number pad rather than the full
    // keyboard — otherwise the caller has to hunt for digits on a text layout.
    keyboardType: KeyboardType = KeyboardType.Text,
    // Applied to the INNER text field rather than the outer Column, which is the only way a
    // caller can attach a FocusRequester that actually reaches the input. `modifier` lands on
    // the Column and cannot take focus. Defaults to empty, so every existing call site is
    // byte-for-byte unchanged.
    textFieldModifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
            trailingIcon = trailingIcon,
            isError = isError,
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize() // Ensure accessible sizes (48dp+)
                .then(textFieldModifier),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                errorBorderColor = MaterialTheme.colorScheme.error
            )
        )
        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun StatCard(
    value: String,
    label: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val cardModifier = if (onClick != null) {
        modifier.clickable { onClick() }
    } else {
        modifier
    }

    Card(
        modifier = cardModifier
            .padding(4.dp)
            .widthIn(min = 100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .padding(2.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun HorizontalActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTagStr: String = ""
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(testTagStr)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    status: String,
    modifier: Modifier = Modifier
) {
    val containerColor = when (status.lowercase()) {
        "active", "online", "success", "approved" -> Color(0xFFE8F5E9)
        "pending", "warning" -> Color(0xFFFFF3E0)
        "inactive", "offline", "rejected", "archived", "disabled" -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when (status.lowercase()) {
        "active", "online", "success", "approved" -> Color(0xFF2E7D32)
        "pending", "warning" -> Color(0xFFEF6C00)
        "inactive", "offline", "rejected", "archived", "disabled" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
