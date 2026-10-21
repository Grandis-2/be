package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * catalog 에 회차 일정 변경 알리기({@link PreorderCampaignScheduleChanged}).
 *
 * @param enabled 보내기를 켠다. 기본은 꺼짐 — catalog 는 처리기가 없는 종류를 재시도 뒤 DLQ 로 보내므로 catalog 처리기가 배포된 뒤 켠다.
 *                꺼진 동안의 변경은 켠 뒤 보내지 않는다 — 전체 재발행으로 맞춘다
 */
@ConfigurationProperties("nova.campaign.schedule-notice")
record ScheduleNoticeProperties(@DefaultValue("false") boolean enabled) {
}
