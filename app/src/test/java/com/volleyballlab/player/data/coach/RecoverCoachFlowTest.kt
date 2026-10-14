package com.volleyballlab.player.data.coach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverCoachFlowTest {
    @Test fun rejectedReadUnblocksScreenAndReconnects() = runTest {
        var attempts = 0
        var failure: Throwable? = null
        val values = mutableListOf<List<String>>()
        val pending = launch {
            flow {
                if (attempts++ == 0) error("permission rejected")
                emit(listOf("real coach"))
            }.recoverCoachFlow(emptyList(), { failure = it }, { failure = null }).toList(values)
        }
        runCurrent()
        assertEquals(listOf(emptyList<String>()), values)
        assertTrue(failure != null)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(emptyList(), listOf("real coach")), values)
        assertEquals(null, failure)
        pending.join()
    }

    @Test fun signOutCancellationDoesNotRetryOrBecomeConnectionError() = runTest {
        var attempts = 0
        var failures = 0
        val pending = launch {
            flow<Int> { attempts++; throw CancellationException("account changed") }
                .recoverCoachFlow(0, { failures++ }, {}).toList()
        }
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }

    @Test fun successfulReadPreservesDataWithoutFallback() = runTest {
        var recoveries = 0
        val result = flow { emit("coach A"); emit("coach B") }
            .recoverCoachFlow("empty", { error("unexpected failure") }, { recoveries++ }).toList()
        assertEquals(listOf("coach A", "coach B"), result)
        assertEquals(2, recoveries)
    }
}
