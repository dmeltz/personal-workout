package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Parses a weight typed with a dot or a comma. Null unless it's a plausible body weight. */
fun parseKg(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..250.0 }

/** A weight field with a Save button, prefilled with the last weigh-in. */
@Composable
fun WeightInput(last: Double?, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(last?.let { "%.1f".format(it) } ?: "") }
    val kg = parseKg(text)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Weight (kg)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(150.dp),
        )
        Button(onClick = { kg?.let(onSave) }, enabled = kg != null) { Text("Save") }
    }
}

/** The weekly weigh-in prompt on the home screen. */
@Composable
fun WeighInCard(last: Double?, onSave: (Double) -> Unit, onLater: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weekly weigh-in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Weigh yourself in the morning, after the toilet and before eating, so the weeks compare fairly." +
                    (last?.let { " Last time: %.1f kg.".format(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            WeightInput(last, onSave)
            TextButton(onClick = onLater) { Text("Not now") }
        }
    }
}
