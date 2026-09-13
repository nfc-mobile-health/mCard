package com.smaple.mcard.ui

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun Navigation() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val mainViewModel: MainViewModel = hiltViewModel()

    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        } else {
            navController.navigate("status") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) "status" else "login"
    ) {
        composable("login") { LoginScreen(authViewModel, navController) }
        composable("register") { RegisterScreen(authViewModel, navController) }
        composable("status") { StatusScreen(authViewModel, mainViewModel, navController) }
        composable("history") { HistoryScreen(mainViewModel, navController) }
    }
}
