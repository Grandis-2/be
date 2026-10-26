package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Lifecycle;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 알림 목록 · 실시간 연결의 항목 하나(요청서의 제안 모양에 raisedAt · resolvable 을 더했다). 화면 문구 · 갈 곳은 프론트가 type · target ·
 * metrics 로 만든다.
 *
 * @param target     대상. 대상이 없는 알림은 kind=NONE · id=null 이다. name 은 받지 못했으면(비공개 상품 등) null
 * @param raisedAt   마지막으로 "새 알림"으로 올린 시각. 목록이 이 순서다
 * @param resolvable false 면 한 번 오는 소식이라 넣을 때부터 RESOLVED 다 — 해소 문구를 보이지 않는다
 * @param readAt     읽은 시각(전역 하나). 다시 올리면 null 로 돌아간다
 */
public record AdminAlertResponse(UUID notificationId, String type, Audience audience, Severity severity, Status status, Target target,
                                 JsonNode metrics, boolean resolvable, Instant firstOccurredAt, Instant lastUpdatedAt, Instant raisedAt,
                                 Instant resolvedAt, Instant readAt) {

    public record Target(String kind, String id, String name) {
    }

    /** 안 읽은 수(받는 사람 → 심각도별). 실시간 연결의 unread 사건과 같은 모양이다. */
    public record UnreadCounts(long urgent, long check, long info) {

        static UnreadCounts of(Map<Severity, Long> counts) {
            return new UnreadCounts(counts.get(Severity.URGENT), counts.get(Severity.CHECK), counts.get(Severity.INFO));
        }
    }

    static AdminAlertResponse of(AdminAlertQueries.View view, JsonMapper jsonMapper) {
        boolean none = AdminAlertRules.TargetKind.NONE.name().equals(view.targetKind());
        Target target = new Target(view.targetKind(), none ? null : view.targetId(), view.targetName());
        boolean resolvable = AdminAlertRules.of(view.type(), view.source()).lifecycle() == Lifecycle.ONGOING;
        return new AdminAlertResponse(view.id(), view.type(), view.audience(), view.severity(), view.status(), target,
                jsonMapper.readTree(view.metrics()), resolvable, view.firstOccurredAt(), view.lastUpdatedAt(), view.raisedAt(),
                view.resolvedAt(), view.readAt());
    }
}
