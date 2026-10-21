package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인프라에 회차 일정 · 예상 방문자 수 알리기({@link PreorderCampaignScalingPlanned}).
 *
 * @param enabled 보내기를 켠다. 기본은 꺼짐 — 받을 큐(scaling-events)와 preorder 의 보내기 권한이 생긴 뒤 켠다.
 *                꺼진 동안의 변경은 켠 뒤 보내지 않는다 — 전체 재발행으로 맞춘다
 */
@ConfigurationProperties("nova.campaign.scaling-notice")
record ScalingNoticeProperties(@DefaultValue("false") boolean enabled) {
}
