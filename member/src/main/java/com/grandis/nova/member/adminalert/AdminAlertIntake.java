package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 두 경로에서 푼 사건을 저장한다. 새 사건이면 저장 전에 대상 이름을 받는다 — 트랜잭션 밖에서 불러 DB 연결을 상대 응답 시간만큼 쥐지 않는다.
 * 이미 있는 사건(숫자 갱신 · 해소 · 중복)은 이름을 다시 묻지 않는다.
 */
@Component
class AdminAlertIntake {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertIntake.class);

    static final String METRIC = "member.admin-alerts.received";
    /** 모르는 type 은 이 태그로 센다 — 보내는 쪽 문자열을 그대로 태그로 쓰면 시계열이 끝없이 는다 */
    static final String OTHER_TYPE = "OTHER";

    private final AdminAlertWriter writer;
    private final TargetNames names;
    private final MeterRegistry meterRegistry;

    AdminAlertIntake(AdminAlertWriter writer, TargetNames names, MeterRegistry meterRegistry) {
        this.writer = writer;
        this.names = names;
        this.meterRegistry = meterRegistry;
    }

    void accept(AdminAlertSignal signal) {
        boolean known = AdminAlertRules.known(signal.type());
        if (!known) {
            log.warn("모르는 관리자 알림 type 이다 — 개발자 · 확인으로 저장한다 type={} source={}", signal.type(), signal.source());
        }
        String name = null;
        if ((signal.targetKind() == TargetKind.PRODUCT || signal.targetKind() == TargetKind.DRAW) && writer.isNew(signal)) {
            name = names.find(signal.targetKind(), signal.targetId()).name();
        }
        AdminAlertWriter.Outcome outcome = writer.apply(signal, name);
        meterRegistry.counter(METRIC, "source", signal.source().name(), "type", known ? signal.type() : OTHER_TYPE,
                "outcome", outcome.name().toLowerCase()).increment();
        log.info("관리자 알림 받음 source={} type={} target={}:{} episode={} resolved={} outcome={} eventId={}", signal.source(),
                signal.type(), signal.targetKind(), signal.targetId(), signal.episode(), signal.resolved(), outcome, signal.eventId());
    }
}
