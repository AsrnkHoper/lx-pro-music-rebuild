package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.designsystem.theme.LXPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** null（从未选择）与未知 id 一律回落 ④ 白·留白极简 */
    val palette: StateFlow<LXPalette> = settings.paletteId
        .map { LXPalette.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LXPalette.default)

    val ambientEnabled: StateFlow<Boolean> = settings.ambientEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun selectPalette(palette: LXPalette) {
        viewModelScope.launch { settings.setPaletteId(palette.id) }
    }

    fun setAmbientEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAmbientEnabled(enabled) }
    }
}