package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AnswerRecord
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Mastery
import com.dmeltz.personalworkout.core.Rule
import com.dmeltz.personalworkout.core.RuleDetails
import com.dmeltz.personalworkout.today
import java.time.LocalDate

@Composable
fun RuleScreen(rule: Rule, details: RuleDetails?, state: AppState, answers: List<AnswerRecord>, onBack: () -> Unit) {
    val stats = state.ruleStats(rule.id)
    val score = Mastery.score(stats, today())
    val mistakes = answers.filter { !it.correct && !it.retry }.sortedByDescending { it.timestamp }.take(15)
    Page(title = rule.id, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(rule.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (stats.introduced) {
                Text(
                    "Score ${Math.round(score * 100)}% · ${stats.correctAnswers} of ${stats.answers} answers right",
                    color = scoreColor(score),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text("Not started yet", style = MaterialTheme.typography.titleMedium)
            }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { RuleText(rule) } }
            details?.let {
                Text("In detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                RuleDetailsView(it)
            }
            if (mistakes.isNotEmpty()) {
                Text("Recent mistakes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                for (m in mistakes) {
                    Column {
                        Text(LocalDate.ofEpochDay(m.day).toString(), style = MaterialTheme.typography.labelSmall)
                        Text("You wrote: ${m.typed}", color = Bad)
                        Text("Correct: ${m.expected}", color = Good)
                    }
                }
            }
        }
    }
}
