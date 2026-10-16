package com.grandis.nova.waitingroom.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 회차 일정 재발행 요청 설정. 기본값은 코드에 둔다.
 *
 * @param periodic 일정을 아는 동안에도 전체 재발행을 요청하는 주기. 0 이면 끈다
 */
@ConfigurationProperties("waitingroom.schedule.resync")
public record ScheduleResyncProperties(Duration periodic) {

    public ScheduleResyncProperties {
        // 놓친 일정 메시지는 길어야 10분 뒤에 메워진다. preorder 가 회차 수만큼 다시 보내므로 더 잦게 하지 않는다
        periodic = periodic == null ? Duration.ofMinutes(10) : periodic;
        if (periodic.isNegative() || (!periodic.isZero() && periodic.compareTo(ScheduleResync.CLAIM_TTL) < 0)) {
            throw new IllegalArgumentException("waitingroom.schedule.resync.periodic 은 0(끔)이거나 "
                    + ScheduleResync.CLAIM_TTL + " 이상이어야 한다: " + periodic);
        }
    }

    boolean periodicEnabled() {
        return !periodic.isZero();
    }
}
