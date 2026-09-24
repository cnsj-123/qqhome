package com.qq.closie.life.ui.shell

import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * Timeline grouping is pure date maths, so it is tested here directly rather than through Compose.
 * A fixed zone keeps the assertions deterministic regardless of the machine running the build.
 */
class TimelineGroupingTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun record(id: String, epochMillis: Long): CaptureItemEntity =
        CaptureItemEntity(
            id = id,
            source = CaptureSource.MANUAL,
            status = CaptureStatus.NEW,
            createdAt = epochMillis,
            updatedAt = epochMillis
        )

    /**
     * Builds an instant from a *local* wall-clock time in [zone].
     *
     * Parsing a UTC string instead would be a trap: 21:00Z is already the next calendar day in
     * Asia/Shanghai, so a test that meant "two records, one day" would silently create two buckets.
     */
    private fun at(day: String, hour: Int): Long =
        LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun emptyInputProducesNoGroups() {
        assertThat(groupByDate(emptyList(), zone)).isEmpty()
    }

    @Test
    fun recordsOnTheSameDayShareOneBucket() {
        val groups = groupByDate(
            listOf(
                record("a", at("2026-03-24", 9)),
                record("b", at("2026-03-24", 21))
            ),
            zone
        )

        assertThat(groups).hasSize(1)
        assertThat(groups[0].first).isEqualTo(LocalDate.of(2026, 3, 24))
        // Newest first inside the day: the 21:00 record precedes the 09:00 one.
        assertThat(groups[0].second.map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun newestRecordFirstInsideADay() {
        val groups = groupByDate(
            listOf(
                record("early", at("2026-03-24", 8)),
                record("late", at("2026-03-24", 20))
            ),
            zone
        )

        assertThat(groups[0].second.map { it.id }).containsExactly("late", "early").inOrder()
    }

    @Test
    fun newestDayFirstAcrossBuckets() {
        val groups = groupByDate(
            listOf(
                record("older", at("2026-03-22", 10)),
                record("newest", at("2026-03-24", 10)),
                record("middle", at("2026-03-23", 10))
            ),
            zone
        )

        assertThat(groups.map { it.first })
            .containsExactly(
                LocalDate.of(2026, 3, 24),
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 3, 22)
            )
            .inOrder()
    }
}
