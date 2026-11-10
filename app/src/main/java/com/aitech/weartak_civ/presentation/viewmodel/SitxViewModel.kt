/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import com.aitech.weartak_civ.service.sitx.SitxServiceEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SitxViewModel : ViewModel() {

    private val _lastEvent = MutableStateFlow<SitxServiceEvent?>(null)
    val lastEvent: StateFlow<SitxServiceEvent?> = _lastEvent.asStateFlow()

    init {
        viewModelScope.launch {
            SitxServiceEventBus.events.collect { event ->
                _lastEvent.value = event
            }
        }
    }
}
