package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GraphScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Knowledge Graph", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("מסך ה-Graph מוכן לחיבור ל-/api/v1/graph/ui.")
        Text("Nodes / Edges יוצגו כאן בגרסת הוויזואליזציה הבאה.")
    }
}
