package io.github.aigoodle.trigger.cron;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerScheduleTest {

    @Test
    void calculatesDailyScheduleInConfiguredTimeZone() {
        TriggerSchedule schedule = TriggerSchedule.from(Map.of(
                "scheduleType", "CRON", "expression", "0 0 8 * * *",
                "timeZone", "Asia/Shanghai"));
        assertThat(schedule.firstFireAt(LocalDateTime.of(2026, 8, 17, 7, 0)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 8, 0));
    }

    @Test
    void oneTimeScheduleHasNoSecondOccurrence() {
        TriggerSchedule schedule = TriggerSchedule.from(Map.of(
                "scheduleType", "ONCE", "runAt", "2026-08-18T08:00:00+08:00"));
        assertThat(schedule.oneTime()).isTrue();
        assertThat(schedule.nextAfter(LocalDateTime.now())).isNull();
    }

    @Test
    void rejectsPastOneTimeSchedule() {
        TriggerSchedule schedule = TriggerSchedule.from(Map.of(
                "scheduleType", "ONCE", "runAt", "2020-01-01T00:00:00Z"));
        assertThatThrownBy(() -> schedule.firstFireAt(LocalDateTime.now()))
                .hasMessageContaining("future");
    }
}
