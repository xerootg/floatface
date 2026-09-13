package app.floatface.wear.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.floatface.core.BoardConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `empty config shows not-set and any-board, never the raw bytes`() {
        composeRule.setContent {
            ConfigScreen(
                config = BoardConfig.EMPTY,
                onEditUnlockBytes = {},
                onEditBleMac = {},
                onClearConfig = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Unlock bytes: not set").assertExists()
        composeRule.onNodeWithText("any Onewheel", substring = true).assertExists()
    }

    @Test
    fun `configured board shows set and the MAC without leaking the bytes`() {
        val hex = "0102030405060708090a0b0c0d0e0f1011121314"
        composeRule.setContent {
            ConfigScreen(
                config = BoardConfig(unlockBytesHex = hex, bleMac = "AA:BB:CC:DD:EE:FF"),
                onEditUnlockBytes = {},
                onEditBleMac = {},
                onClearConfig = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Unlock bytes: set").assertExists()
        composeRule.onNodeWithText("AA:BB:CC:DD:EE:FF", substring = true).assertExists()
        // The sensitive hex must never be rendered.
        composeRule.onAllNodesWithTextSafe(hex)
    }

    @Test
    fun `edit and clear buttons fire their callbacks`() {
        var editedUnlock = false
        var editedMac = false
        var cleared = false
        composeRule.setContent {
            ConfigScreen(
                config = BoardConfig.EMPTY,
                onEditUnlockBytes = { editedUnlock = true },
                onEditBleMac = { editedMac = true },
                onClearConfig = { cleared = true },
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Set unlock bytes").performClick()
        composeRule.onNodeWithText("Set BLE MAC").performClick()
        composeRule.onNodeWithText("Clear").performClick()
        assertTrue(editedUnlock && editedMac && cleared)
    }

    /** Asserts the given text does NOT appear anywhere in the tree. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(text: String) {
        val nodes = onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
        assertTrue("sensitive text must not be rendered", nodes.isEmpty())
    }
}
