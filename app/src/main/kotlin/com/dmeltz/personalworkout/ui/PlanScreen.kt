package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.DayKind
import com.dmeltz.personalworkout.core.Group
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Planner
import com.dmeltz.personalworkout.today
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private fun dayName(day: Long) = LocalDate.ofEpochDay(day).format(dayFormat)

/** The next 10 days, by day or by exercise, if every exercise goes as planned. */
@Composable
fun PlanScreen(state: AppState, library: Library, onBack: () -> Unit) {
    val plan = remember(state) { Planner.nextDays(library, state, today(), 10) }
    var tab by remember { mutableIntStateOf(0) }
    Page(title = "Next 10 days", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("By day") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("By exercise") })
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "A forecast: it assumes you do every exercise in full at its target and tap OK. " +
                            "Easy, Hard, skips and capoeira days you add will move it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (tab == 0) items(plan, key = { it.day }) { d -> DayCard(d, library) }
                else for (g in Group.entries) {
                    val exercises = library.inGroup(g).filter { ex -> plan.any { d -> d.items.any { it.exerciseId == ex.id } } }
                    if (exercises.isEmpty()) continue
                    item(key = g.name) {
                        Text(
                            g.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = groupColors.getValue(g),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    items(exercises, key = { it.id }) { ex ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ex.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                for (d in plan) for (it in d.items.filter { it.exerciseId == ex.id }) {
                                    Row {
                                        Text(dayName(d.day), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                                        Text(
                                            "${ex.variation(it.variation).name}: ${it.sets} × ${ex.amount(it.variation, it.target)}" +
                                                if (it.new) "  · new" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCard(d: Planner.DayPlan, library: Library) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row {
                Text(dayName(d.day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    when (d.kind) {
                        DayKind.REST -> "Rest day"
                        DayKind.CAPOEIRA -> "Capoeira · short, optional"
                        DayKind.AFTER_CAPOEIRA -> "After capoeira · ~${d.minutes} min"
                        DayKind.NORMAL -> "~${d.minutes} min"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            for (it in d.items) {
                val ex = library.exercise(it.exerciseId) ?: continue
                Row {
                    Text("● ", color = groupColors.getValue(ex.group))
                    Text(
                        "${ex.variation(it.variation).name}: ${it.sets} × ${ex.amount(it.variation, it.target)}" + if (it.new) "  · new" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
