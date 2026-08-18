package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProjectsScreen() {
    var projectName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(projectName, { projectName = it }, label = { Text("שם פרויקט") })
        Spacer(Modifier.height(8.dp))
        Button(onClick = { /* connect POST /api/v1/projects */ }) { Text("צור פרויקט") }
        Spacer(Modifier.height(20.dp))
        Text("כאן יוצגו Projects ו-Cases מהשרת.")
    }
}
