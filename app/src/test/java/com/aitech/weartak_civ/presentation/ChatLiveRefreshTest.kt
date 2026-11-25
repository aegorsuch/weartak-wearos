package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatLiveRefreshTest {
    private val compose = createComposeRule()
    @get:Rule val rules = RuleChain.outerRule(registerComposeTestActivity()).around(compose)

    private fun message(number: Int) = ChatMessage(
        "Message $number", "alice", "Alice", number.toLong(), messageId = number.toString()
    )

    @Test fun newMessagesFollowNewestButDoNotInterruptReadingHistory() {
        MainActivity.device_uid = "watch"
        MainActivity.dataRepository = DataRepository(RuntimeEnvironment.getApplication())
        var messages by mutableStateOf((1..20).map(::message))
        compose.setContent {
            val room = ChatRoom("alice", "Alice", messages, false, 0, 0)
            ChatScreenDetailed(rememberSwipeDismissableNavController(), "alice", LocalContext.current, listOf(room))
        }

        compose.onNodeWithText("Message 20").assertIsDisplayed()
        compose.runOnIdle { messages = messages + listOf(message(21), message(22)) }
        compose.onNodeWithText("Message 22").assertIsDisplayed()

        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Message 10"))
        compose.onNodeWithText("Message 10").assertIsDisplayed()
        compose.runOnIdle { messages = messages + message(23) }
        compose.onNodeWithText("Message 10").assertIsDisplayed()
    }
}
