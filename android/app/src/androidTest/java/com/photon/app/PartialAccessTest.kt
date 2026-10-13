package com.photon.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photon.app.core.MediaAccess
import com.photon.app.ui.AppViewModel
import com.photon.app.ui.PermissionCards
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 7.13: partial media access (Android 14 "Select photos") shows the prompt for full access.
 * The permission state is injected: revoking a runtime permission kills the app process, which
 * would also kill the instrumentation.
 */
@RunWith(AndroidJUnit4::class)
class PartialAccessTest {
    @get:Rule
    val compose = createComposeRule()

    private val vm by lazy { AppViewModel(ApplicationProvider.getApplicationContext()) }

    @Test
    fun partialAccessShowsThePromptWithSettings() {
        compose.setContent { PermissionCards(vm, accessProvider = { MediaAccess.PARTIAL }) }
        compose.onNodeWithTag("media-access-prompt").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test
    fun fullAccessShowsNoPrompt() {
        compose.setContent { PermissionCards(vm, accessProvider = { MediaAccess.FULL }) }
        compose.onNodeWithTag("media-access-prompt").assertDoesNotExist()
    }
}
