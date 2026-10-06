package com.example.ui.components

import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Barrel/wheel-style date + time picker (the classic rolling-drum widget, same family as the
 * Samsung clock app's picker) — embedded native Android DatePicker/TimePicker in spinner mode,
 * since Compose has no built-in wheel picker. Replaces free-text "YYYY-MM-DD HH:MM" entry, which
 * was both awkward to type and the source of a silent scheduling bug (see toIsoWithOffset).
 *
 * Date and time are shown ONE AT A TIME behind a two-tab switch. They used to be stacked in a
 * single un-scrollable Column, and two full spinner widgets are taller than an AlertDialog is
 * allowed to be on a normal phone: the clock was cut off by the bottom of the screen and the class
 * time could not be set at all. The admin was left saving whatever hour the picker happened to open
 * on — which is how calling session "01-08-26" ended up stamped 31 Jul 17:45, a full day before the
 * class it was named for, and that stale timestamp is what the follow-up expiry sweep then treated
 * as "the class has already happened".
 *
 * One spinner always fits, at any screen height, with no nested scrolling to fight the wheels.
 */
@Composable
fun WheelDateTimePickerDialog(
    initialCalendar: Calendar = Calendar.getInstance(),
    onDismiss: () -> Unit,
    onConfirm: (Calendar) -> Unit,
) {
    var year by remember { mutableStateOf(initialCalendar.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(initialCalendar.get(Calendar.MONTH)) }
    var day by remember { mutableStateOf(initialCalendar.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember { mutableStateOf(initialCalendar.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(initialCalendar.get(Calendar.MINUTE)) }

    // Start on Date; the admin picks the day, then switches to Time.
    var tab by remember { mutableStateOf(0) }

    val picked = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick date & time") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Always show what is currently selected, so the value is confirmable without
                // flipping back and forth between the two tabs.
                Text(
                    formatPickedDateTime(picked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Date") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Time") })
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Only the active spinner is composed. Each AndroidView factory reads the CURRENT
                // state, so switching tabs and back re-seeds the wheel with what was already picked.
                if (tab == 0) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { context ->
                            DatePicker(context).apply {
                                runCatching { calendarViewShown = false }
                                runCatching { spinnersShown = true }
                                init(year, month, day) { _, y, m, d -> year = y; month = m; day = d }
                            }
                        }
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { context ->
                            TimePicker(context).apply {
                                setIs24HourView(false)
                                @Suppress("DEPRECATION")
                                runCatching { currentHour = hour }
                                @Suppress("DEPRECATION")
                                runCatching { currentMinute = minute }
                                setOnTimeChangedListener { _, h, m -> hour = h; minute = m }
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                onConfirm(cal)
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** "Wed, 9 Jul 2026 · 5:30 PM" — the human-readable label for a picked Calendar. */
fun formatPickedDateTime(calendar: Calendar): String =
    SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale.getDefault()).format(calendar.time)

/** "yyyy-MM-dd HH:mm" — exactly one of the formats toIsoWithOffset accepts, built directly from
 *  discrete picker fields so there is no free-text parsing ambiguity. */
fun formatForScheduling(calendar: Calendar): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(calendar.time)
