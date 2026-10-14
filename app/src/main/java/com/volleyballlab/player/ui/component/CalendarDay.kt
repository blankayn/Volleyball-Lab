package com.volleyballlab.player.ui.component

import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Emits a new calendar day while a screen stays open across midnight. */
fun calendarDayFlow() = flow {
    while (true) { emit(LocalDate.now()); delay(60_000) }
}.distinctUntilChanged()
