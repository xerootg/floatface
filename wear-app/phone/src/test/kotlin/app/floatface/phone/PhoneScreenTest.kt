package app.floatface.phone

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.floatface.phone.sync.WatchAppStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val validHex = "0102030405060708090a0b0c0d0e0f1011121314"

    @Test
    fun `send is disabled until the form is valid`() {
        composeRule.setContent {
            PhoneScreen(
                state = PhoneUiState(unlockHex = "abc"), // invalid (odd length)
                onUnlockHexChange = {},
                onBleMacChange = {},
                onSend = {},
            )
        }
        composeRule.onNodeWithText("Send to watch").assertIsNotEnabled()
    }

    @Test
    fun `send is enabled with valid unlock and blank MAC`() {
        composeRule.setContent {
            PhoneScreen(
                state = PhoneUiState(unlockHex = validHex),
                onUnlockHexChange = {},
                onBleMacChange = {},
                onSend = {},
            )
        }
        composeRule.onNodeWithText("Send to watch").assertIsEnabled()
    }

    @Test
    fun `status text reflects the send result`() {
        assertTrue(statusText(SendStatus.Sent(1)).contains("Sent"))
        assertTrue(statusText(SendStatus.NoWatch).contains("No watch"))
        assertTrue(statusText(SendStatus.Failed("boom")).contains("boom"))
    }

    @Test
    fun `install button shows only when the watch app is missing`() {
        composeRule.setContent {
            PhoneScreen(
                state = PhoneUiState(
                    unlockHex = validHex,
                    watchAppStatus = WatchAppStatus.NotInstalled(listOf("node-1")),
                ),
                onUnlockHexChange = {},
                onBleMacChange = {},
                onSend = {},
            )
        }
        composeRule.onNodeWithText("Install watch app").assertIsEnabled()
    }

    @Test
    fun `install button hidden when the watch app is installed`() {
        composeRule.setContent {
            PhoneScreen(
                state = PhoneUiState(
                    unlockHex = validHex,
                    watchAppStatus = WatchAppStatus.Installed(1),
                ),
                onUnlockHexChange = {},
                onBleMacChange = {},
                onSend = {},
            )
        }
        composeRule.onNodeWithText("Install watch app").assertDoesNotExist()
    }
}
