package com.aitech.weartak_civ.presentation

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

internal fun Modifier.automationRoot(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }

internal fun serverFormIsValid(
    name: String,
    address: String,
    port: String,
    username: String,
    password: String,
    credentialsOptional: Boolean,
): Boolean = name.isNotBlank() && address.isNotBlank() && port.toIntOrNull() in 1..65535 &&
    (credentialsOptional || (username.isNotBlank() && password.isNotBlank()))
