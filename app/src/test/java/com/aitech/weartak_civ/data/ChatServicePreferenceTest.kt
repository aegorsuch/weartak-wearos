package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatServicePreferenceTest {
    @Test
    fun managedSettingOverridesChatAndMissingSettingPreservesSavedValue() = runBlocking {
        MainActivity.device_uid = "test-chat-service"
        val repository = DataRepository(RuntimeEnvironment.getApplication())

        assertTrue(repository.applyManagedChatServiceSetting(managedSetting = null))
        assertFalse(repository.applyManagedChatServiceSetting(managedSetting = false))
        assertFalse(repository.getEnableChatService.first())
        assertFalse(repository.applyManagedChatServiceSetting(managedSetting = null))
        assertTrue(repository.applyManagedChatServiceSetting(managedSetting = true))
        assertTrue(repository.getEnableChatService.first())
    }
}
