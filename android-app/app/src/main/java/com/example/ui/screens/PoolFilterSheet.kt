package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AppDatabase
import com.example.data.PoolFilter
import com.example.data.PoolFilterTicks
import com.example.data.SessionResponseOption
import com.example.data.SessionResponses

/**
 * The Pool page's filter sheet: which responses (by group, or singly) and which attendance.
 * Replaces the Attended / Positive response / Need check chips — see PoolFilter for why.
 *
 * Works on a draft; nothing changes on the page until Apply, so an admin ticking five boxes does
 * not set off five reloads of the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PoolFilterSheet(
    sessionIds: Set<String>,
    initial: PoolFilter,
    onApply: (PoolFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var data by remember { mutableStateOf<SessionResponses?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Single responses stay folded away unless a filter already uses them.
    var showSingles by remember { mutableStateOf(initial.responseIds.isNotEmpty()) }

    LaunchedEffect(sessionIds) {
        loadFailed = false
        val r = AppDatabase.fetchSessionResponses(sessionIds)
        if (r == null) loadFailed = true else data = r
    }
    val options = data?.responses.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter people") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val n = sessionIds.size
                Text(
                    "From the $n selected session${if (n == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("Response when called", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap Positive, Negative or Neutral to take every response in it. Nothing picked = any response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                // One tap per meaning group. Shown even when the response list below could not load:
                // a whole group is sent as its name, so it does not need the list.
                if (data?.anyCalling != false) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sentiment in PoolFilter.SENTIMENTS) {
                            val groupState = PoolFilterTicks.groupState(draft, options, sentiment)
                            val count = data?.let { d ->
                                options.filter { it.sentiment == sentiment }.sumOf { it.count } +
                                    if (sentiment == PoolFilter.SENTIMENT_NEUTRAL) d.neverCalledCount else 0
                            }
                            FilterChip(
                                selected = groupState != PoolFilterTicks.State.NONE,
                                // With some singles picked, a tap takes the whole group.
                                onClick = { draft = PoolFilterTicks.toggleGroup(draft, options, sentiment) },
                                label = {
                                    Text(
                                        PoolFilter.GROUP_NAMES.getValue(sentiment) + (count?.let { " ($it)" } ?: "") +
                                            if (groupState == PoolFilterTicks.State.SOME) " · some" else "",
                                    )
                                },
                            )
                        }
                    }
                }
                if (data != null && data?.anyCalling != false) {
                    TextButton(onClick = { showSingles = !showSingles }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showSingles) "Hide single responses" else "Pick single responses instead…")
                    }
                }

                when {
                    loadFailed -> Text(
                        "Couldn't load the list of single responses — the calling backend didn't answer. " +
                            "The Positive / Negative / Neutral buttons still work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    data == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading counts…", style = MaterialTheme.typography.bodySmall)
                    }
                    data?.anyCalling == false -> Text(
                        "No calling was done for these sessions, so there are no responses to filter by. " +
                            "You can still filter by attendance below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !showSingles -> {}
                    else -> {
                        for (sentiment in PoolFilter.SENTIMENTS) {
                            val inGroup = options.filter { it.sentiment == sentiment }
                            val neverCalledHere = sentiment == PoolFilter.SENTIMENT_NEUTRAL
                            val neverCount = if (neverCalledHere) data?.neverCalledCount ?: 0 else 0
                            val total = inGroup.sumOf { it.count } + neverCount
                            val open = sentiment in expanded
                            val state = when (PoolFilterTicks.groupState(draft, options, sentiment)) {
                                PoolFilterTicks.State.ALL -> ToggleableState.On
                                PoolFilterTicks.State.SOME -> ToggleableState.Indeterminate
                                PoolFilterTicks.State.NONE -> ToggleableState.Off
                            }
                            val toggleOpen = { expanded = if (open) expanded - sentiment else expanded + sentiment }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { draft = PoolFilterTicks.toggleGroup(draft, options, sentiment) },
                                        onLongClick = toggleOpen,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(state = state, onClick = { draft = PoolFilterTicks.toggleGroup(draft, options, sentiment) })
                                Text(
                                    "${PoolFilter.GROUP_NAMES.getValue(sentiment)} ($total)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = toggleOpen) {
                                    Icon(
                                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (open) "Hide responses" else "Show responses",
                                    )
                                }
                            }
                            if (open) {
                                if (inGroup.isEmpty() && neverCount == 0) {
                                    Text(
                                        "Nobody gave a ${PoolFilter.GROUP_NAMES.getValue(sentiment).lowercase()} response.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
                                    )
                                }
                                for (opt in inGroup) {
                                    ResponseRow(
                                        label = opt.label,
                                        count = opt.count,
                                        checked = PoolFilterTicks.isResponseTicked(draft, opt),
                                        onToggle = { draft = PoolFilterTicks.toggleResponse(draft, options, opt) },
                                    )
                                }
                                // Allotted but never rung: not a response, so it has no sentiment,
                                // but it is the same "we never got through" question as Neutral.
                                if (neverCalledHere && neverCount > 0) {
                                    ResponseRow(
                                        label = "Not called yet",
                                        count = neverCount,
                                        checked = draft.neverCalled,
                                        onToggle = { draft = PoolFilterTicks.toggleNeverCalled(draft, options) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Attendance at that session", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tick both, or neither, for everyone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.attended, onCheckedChange = { draft = draft.copy(attended = it) })
                    Text("Attended", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = draft.notAttended, onCheckedChange = { draft = draft.copy(notAttended = it) })
                    Text("Did not attend", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(8.dp))
                val labels = options.flatMap { o -> o.optionIds.map { it to o.label } }.toMap()
                Text(
                    "Showing: ${draft.describe(labels)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = PoolFilter.DEFAULT }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ResponseRow(label: String, count: Int, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text("$label ($count)", style = MaterialTheme.typography.bodySmall)
    }
}
