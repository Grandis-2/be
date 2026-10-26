package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 받은 메시지에서 꺼낸 사건 하나. 두 경로(이벤트 · Grafana)가 같은 모양으로 {@link AdminAlertWriter} 에 넘긴다.
 *
 * @param targetId   대상이 없으면 ""
 * @param episode    사건의 이름(Grafana: startsAt, 이벤트: 회차 id)
 * @param resolved   Grafana resolved 면 true
 * @param occurredAt 사건이 시작된 시각(Grafana startsAt, 이벤트 occurredAt)
 * @param resolvedAt 해소 시각(resolved 일 때만)
 * @param seenAt     이 값을 알아챈 시각. 열린 알림의 마지막 갱신 시각이 되고, 이보다 늦은 갱신이 이미 있으면 숫자를 덮지 않는다
 * @param eventId    이벤트 경로의 eventId(추적용). Grafana 는 null
 */
record AdminAlertSignal(Source source, String type, TargetKind targetKind, String targetId, String episode, boolean resolved,
                        Instant occurredAt, Instant resolvedAt, Instant seenAt, ObjectNode metrics, String eventId) {
}
