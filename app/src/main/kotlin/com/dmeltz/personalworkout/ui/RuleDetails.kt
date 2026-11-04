package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Rule
import com.dmeltz.personalworkout.core.RuleDetails

/** The long explanation of a rule with right and wrong examples. */
@Composable
fun RuleDetailsView(details: RuleDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        details.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }

        Text("Right", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Good,
            modifier = Modifier.padding(top = 8.dp))
        details.good.forEach { example ->
            Row {
                Text("✓", color = Good, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                Text(example, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Text("Wrong", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Bad,
            modifier = Modifier.padding(top = 8.dp))
        details.bad.forEach { example ->
            Row {
                Text("✗", color = Bad, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                Column {
                    Text(
                        example.wrong,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Bad,
                        textDecoration = TextDecoration.LineThrough,
                    )
                    Text("→ ${example.right}", style = MaterialTheme.typography.bodyLarge, color = Good)
                    example.why?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Full-screen "More detail" for a rule, opened from a practice session. */
@Composable
fun RuleDetailsDialog(rule: Rule, details: RuleDetails?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PipSays(Mood.CALM, Mascot.explaining())
                Text(rule.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                RuleText(rule)
                details?.let { RuleDetailsView(it) }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) {
                    Text("Got it")
                }
            }
        }
    }
}
