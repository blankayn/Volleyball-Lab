package com.volleyballlab.player.data.coach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** A rejected listener must unblock its screen and restart after connectivity recovers. */
internal fun <T> Flow<T>.recoverCoachFlow(
    fallback: T,
    onFailure: (Throwable) -> Unit,
    onRecovered: () -> Unit,
): Flow<T> = onEach { onRecovered() }.retryWhen { failure, attempt ->
    if (failure is CancellationException) throw failure
    onFailure(failure)
    emit(fallback)
    delay((3_000L * (attempt + 1)).coerceAtMost(15_000L))
    true
}
