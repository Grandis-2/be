package com.grandis.nova.batch.reconciliation.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/**
 * 정합성 검사 실행 설정. 값의 근거는 정합성-검사-구현-조치 6장(10-12 결정)이다.
 *
 * @param schedulingEnabled          주기 작업(요청 확인 · 정기 실행 · 마감 뒤 실행)을 켠다. 시험은 끄고 직접 부른다
 * @param cron                       정기 실행 시각. 접수 · worker 처리가 가장 적은 새벽
 * @param zone                       cron 을 읽는 시간대
 * @param requestPollInterval        관리자가 남긴 실행 요청(REQUESTED)을 가져가는 주기
 * @param campaignCloseCheckInterval 마감 뒤 실행할 회차가 있는지 보는 주기
 * @param campaignCloseLookback      이만큼 지난 마감까지만 본다. 처음 배포할 때 옛 회차마다 실행하지 않게
 * @param cutoffMargin               판정 기준 시각 = 시작 − 이 값. 이 뒤에 바뀐 키는 판정을 보류한다.
 *                                   worker 가 등록을 포기하기까지 최대 약 41분이라 45분이다. 마감 뒤 실행도 마감 + 이 값에 돈다
 * @param intakeRushWindow           회차 오픈 뒤 신청이 몰리는 구간. 이 안의 회차가 있으면 정기 · 마감 뒤 실행을 건너뛴다
 * @param chunkSize                  be 예약을 한 번에 읽는 건수
 * @param maxFindings                한 실행의 불일치 상한. 넘으면 판정 불가 — 그만큼 어긋났으면 Mock 주소 · 데이터 설정 오류일 가능성이 크다
 * @param runTimeout                 검사 트랜잭션의 제한 시간. 넘으면 진행 중인 DB 조회를 끊고 판정 불가로 끝낸다. Mock 목록 전체 기한
 *                                   (external.mock.max-duration)보다 길어야 한다. 시작한 지 이 값 + 여유가 넘은 실행 중은 주인 없는 행으로 본다
 */
@ConfigurationProperties("nova.batch.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("true") boolean schedulingEnabled,
        @DefaultValue("0 0 4 * * *") String cron,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("5s") Duration requestPollInterval,
        @DefaultValue("5m") Duration campaignCloseCheckInterval,
        @DefaultValue("1d") Duration campaignCloseLookback,
        @DefaultValue("45m") Duration cutoffMargin,
        @DefaultValue("1h") Duration intakeRushWindow,
        @DefaultValue("1000") int chunkSize,
        @DefaultValue("10000") int maxFindings,
        @DefaultValue("30m") Duration runTimeout
) {

    public ReconciliationProperties {
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("nova.batch.reconciliation.cron 이 cron 식이 아니다: " + cron);
        }
        if (!positive(requestPollInterval) || !positive(campaignCloseCheckInterval)
                || !positive(campaignCloseLookback) || !positive(intakeRushWindow)) {
            throw new IllegalArgumentException("nova.batch.reconciliation 의 주기 · 구간은 0 보다 커야 한다");
        }
        if (cutoffMargin.isNegative()) {
            throw new IllegalArgumentException("nova.batch.reconciliation.cutoff-margin 은 0 이상이어야 한다");
        }
        if (chunkSize < 1 || maxFindings < 1) {
            throw new IllegalArgumentException("nova.batch.reconciliation 의 chunk-size · max-findings 는 1 이상이어야 한다");
        }
        // 트랜잭션 제한 시간은 초 단위다
        if (runTimeout == null || runTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("nova.batch.reconciliation.run-timeout 은 1초 이상이어야 한다");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.isPositive();
    }
}
