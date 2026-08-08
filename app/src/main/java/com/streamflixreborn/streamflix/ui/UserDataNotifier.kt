package com.streamflixreborn.streamflix.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

import kotlinx.coroutines.FlowPreview

object UserDataNotifier {
    private val _updates = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    @OptIn(FlowPreview::class)
    val updates = _updates
        .debounce(300)

    fun notifyChanged() {
        _updates.tryEmit(Unit)
    }
}