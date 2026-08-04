package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Beschriftete Zeile mit Schalter rechts.
 *
 * WARUM ZENTRAL: Vorher stand an jeder dieser Stellen `Row(SpaceBetween) { Column { ... }; Switch }`
 * — und der Column fehlte `weight(1f)`. Eine Row misst gewichtslose Kinder der Reihe nach mit dem
 * verbleibenden Platz: der lange Beschreibungstext nahm sich die volle Kartenbreite, für den
 * Schalter blieb nichts, und er wurde außerhalb der Karte abgesetzt — er klebte am Text und
 * verschwand hinter dem Rand. Zwei Stellen hatten das mit `Modifier.width(240.dp)` bzw.
 * `width(260.dp)` überdeckt; bei ~296dp Karteninnenbreite ragte besonders die 260er samt Schalter
 * wieder heraus, und auf schmaleren Geräten oder bei großer Schrift bricht jede feste Breite.
 *
 * `weight(1f)` dreht die Reihenfolge um: der Schalter bekommt zuerst seine natürliche Breite, der
 * Text den Rest. Das hält bei jeder Displaybreite und jeder Schriftgröße.
 */
@Composable
fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    titleFontWeight: FontWeight = FontWeight.Medium
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // spacedBy statt SpaceBetween: mit weight(1f) bleibt kein freier Platz mehr, den
        // SpaceBetween verteilen könnte - der Abstand muss explizit her, sonst klebt der
        // Schalter am Text.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = titleStyle, fontWeight = titleFontWeight)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
