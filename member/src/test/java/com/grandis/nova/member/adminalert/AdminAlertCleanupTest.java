package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MemberIntegrationTest
@DisplayName("관리자 알림 정리 — 해소 뒤 30일 · 이름 다시 채우기 (실제 MySQL)")
class AdminAlertCleanupTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired JdbcTemplate jdbc;

    final AdminAlertWriterTest.MutableClock clock = new AdminAlertWriterTest.MutableClock(NOW);
    final TargetNames names = mock(TargetNames.class);
    AdminAlertCleanup cleanup;
    AdminAlertEventReceiver events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM admin_alerts");
        cleanup = new AdminAlertCleanup(jdbc, names, clock, new AdminAlertCleanup.Settings(Duration.ofDays(30), 2, 10, Duration.ofDays(1), 20));
        when(names.find(any(), anyString())).thenReturn(TargetNames.Lookup.ABSENT);
        events = new AdminAlertEventReceiver(new AdminAlertIntake(new AdminAlertWriter(jdbc, JSON, clock), names,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), JSON);
    }

    @Test
    @DisplayName("해소된 지 30일이 지난 행만 묶음씩 지우고, 열린 알림은 아무리 오래돼도 둔다")
    void deletesExpired() {
        for (int i = 0; i < 5; i++) {
            events.receive(AdminAlertMessages.Event.draw("DRAW_SETTLED", UUID.randomUUID(), "2026-10-0" + (i + 1) + "T00:00:00Z").body());
        }
        events.receive(AdminAlertMessages.Event.draw("DRAW_SETTLED", UUID.randomUUID(), "2026-11-15T00:00:00Z").body());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", UUID.randomUUID(), "2026-09-01T00:00:00Z").body());

        assertThat(cleanup.deleteExpired()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT type FROM admin_alerts ORDER BY type", String.class))
                .containsExactly("DRAW_DELAYED", "DRAW_SETTLED");
    }

    @Test
    @DisplayName("다시 묻는 대상은 대상 단위로 고른다 — 이름 없는 한 대상의 행이 한도(시험 2)보다 많아도 다른 대상의 자리를 차지하지 않는다")
    void refillPicksTargetsNotRows() {
        cleanup = new AdminAlertCleanup(jdbc, names, clock, new AdminAlertCleanup.Settings(Duration.ofDays(30), 2, 10, Duration.ofDays(1), 2));
        UUID crowded = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", other, "2026-11-30T00:00:00Z").body());
        for (int i = 0; i < 3; i++) {
            events.receive(AdminAlertMessages.Event.preorder("PREORDER_OPENED", crowded, UUID.randomUUID(), "2026-11-30T0" + (i + 1) + ":00:00Z")
                    .body());
        }
        when(names.find(TargetKind.DRAW, other.toString())).thenReturn(new TargetNames.Lookup("매직 키보드", false));
        clearInvocations(names);

        assertThat(cleanup.refillNames()).isEqualTo(1);
        verify(names).find(TargetKind.PRODUCT, crowded.toString());
        verify(names).find(TargetKind.DRAW, other.toString());
    }

    @Test
    @DisplayName("이름이 빈 상품 · 회차 알림 중 하루 안의 것만 다시 묻는다. 없음은 건너뛰고, 실패하면 그 주기를 멈춘다")
    void refillsNames() {
        UUID absent = UUID.randomUUID();
        UUID found = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", old, "2026-11-29T00:00:00Z").body());
        jdbc.update("UPDATE admin_alerts SET created_at = ? WHERE target_id = ?",
                LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC), old.toString());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", found, "2026-11-30T00:00:00Z").body());
        events.receive(AdminAlertMessages.Event.draw("DRAW_DELAYED", absent, "2026-11-30T01:00:00Z").body());
        when(names.find(TargetKind.DRAW, found.toString())).thenReturn(new TargetNames.Lookup("매직 키보드", false));
        clearInvocations(names);

        assertThat(cleanup.refillNames()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_name FROM admin_alerts WHERE target_id = ?", String.class, found.toString()))
                .isEqualTo("매직 키보드");
        verify(names, never()).find(TargetKind.DRAW, old.toString());

        jdbc.update("UPDATE admin_alerts SET target_name = NULL");
        when(names.find(eq(TargetKind.DRAW), anyString())).thenReturn(TargetNames.Lookup.FAILED);
        assertThat(cleanup.refillNames()).isZero();
    }
}
