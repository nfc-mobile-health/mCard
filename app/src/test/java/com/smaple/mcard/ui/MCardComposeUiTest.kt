package com.smaple.mcard.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.smaple.core.fakes.FakeCredentialStore
import com.smaple.core.fakes.FakeBackendApi

@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["androidx.loader.content"])
class MCardComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLoginScreen_Renders() {
        val authViewModel = AuthViewModel(FakeCredentialStore(), FakeBackendApi())
        composeTestRule.setContent {
            val navController = rememberNavController()
            LoginScreen(viewModel = authViewModel, navController = navController)
        }

        // Happy path check
        composeTestRule.onNodeWithText("Login").assertIsDisplayed()
    }
}
