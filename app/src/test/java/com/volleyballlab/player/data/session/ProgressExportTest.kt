package com.volleyballlab.player.data.session

import com.volleyballlab.player.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProgressExportTest {
    @Test fun `export labels score sources and excludes private video paths`() {
        val session = SessionResult("id", Skill.Receiving, "drill", 1000, 5000, emptyList(), videoPath = "/private/account/movie.mp4")
        val exported = ProgressExport.json(listOf(session))
        assertFalse(exported.contains("/private/account"))
        assertEquals("live_estimates", Json.parseToJsonElement(exported).jsonObject["angleMetricsSource"]?.jsonPrimitive?.content)
    }
    @Test fun `csv quotes identifiers and includes final versus live columns`() {
        val session = SessionResult("a,\"b", Skill.Serving, "drill", 1000, 5000, emptyList())
        val csv = ProgressExport.csv(listOf(session))
        assertTrue(csv.contains("\"a,\"\"b\"")); assertTrue(csv.contains("final_score")); assertTrue(csv.contains("live_valid_reps"))
    }
}
