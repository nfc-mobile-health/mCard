package com.smaple.mcard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smaple.mcard.R
import com.smaple.core.protocol.TransferResult
import com.smaple.core.session.SessionEvent
import com.smaple.core.payload.MedicalRecordTextCodec

@Composable
fun LoginScreen(viewModel: AuthViewModel, navController: NavController) {
    var id by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val error by viewModel.loginError.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text(stringResource(R.string.login_id_hint)) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text(stringResource(R.string.login_pin_hint)) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
        }
        Button(onClick = { viewModel.login(id, pin) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.login_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { navController.navigate("register") }) {
            Text(stringResource(R.string.register_toggle))
        }
    }
}

@Composable
fun RegisterScreen(viewModel: AuthViewModel, navController: NavController) {
    var pin by remember { mutableStateOf("") }
    val error by viewModel.loginError.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.register_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = viewModel.registerId.collectAsState().value, onValueChange = { viewModel.registerId.value = it }, label = { Text(stringResource(R.string.login_id_hint)) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = viewModel.registerName.collectAsState().value, onValueChange = { viewModel.registerName.value = it }, label = { Text(stringResource(R.string.register_name_hint)) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text(stringResource(R.string.login_pin_hint)) }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
        }
        Button(onClick = { viewModel.register(pin) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.register_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { navController.popBackStack() }) { Text("Back to Login") }
    }
}

@Composable
fun StatusScreen(authViewModel: AuthViewModel, mainViewModel: MainViewModel, navController: NavController) {
    val sessionState by mainViewModel.sessionState.collectAsState()
    
    LaunchedEffect(sessionState) {
        if (sessionState is SessionEvent.Completed) {
            val result = (sessionState as SessionEvent.Completed).result
            if (result is TransferResult.RecordReceived) {
                // A3.4 Required regression fix implementation
                mainViewModel.storeRecord(result.record)
            }
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        when (sessionState) {
            is SessionEvent.Idle, is SessionEvent.Completed -> {
                Text(stringResource(R.string.status_idle))
                Spacer(modifier = Modifier.height(16.dp))
                // Test utility to simulate a tap since it's passive
                Button(onClick = { mainViewModel.simulatePassiveTap() }) { Text("Simulate Tap") }
            }
            is SessionEvent.Connecting, is SessionEvent.Verifying -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.session_verifying))
            }
            is SessionEvent.Transferring -> {
                LinearProgressIndicator()
                Text(stringResource(R.string.session_transferring))
            }
            is SessionEvent.Error -> {
                Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
                Button(onClick = { mainViewModel.simulatePassiveTap() }) { Text("Reset") }
            }
            else -> {}
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedButton(onClick = { navController.navigate("history") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.status_history_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { authViewModel.logout() }) {
            Text(stringResource(R.string.home_logout))
        }
    }
}

@Composable
fun HistoryScreen(mainViewModel: MainViewModel, navController: NavController) {
    val recordsMap by mainViewModel.localRecords.collectAsState()
    val error by mainViewModel.historyError.collectAsState()
    
    LaunchedEffect(Unit) {
        mainViewModel.loadHistory()
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = { mainViewModel.loadHistory() }) { Text("Retry Sync") }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(recordsMap.values.toList().sortedByDescending { it.id }) { rec ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // A3.5: Read-only human-readable detail view via Codec
                            Text(MedicalRecordTextCodec.encode(rec), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Back")
        }
    }
}
