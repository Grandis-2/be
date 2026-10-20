package com.grandis.nova.batch.imagecleanup;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/**
 * 안 쓰는 사진 정리 설정. 기본값이 운영값이라 바꿀 때만 적는다. 값이 어긋나면 batch 가 뜨지 않는다 — 정합성 검사도 같은 앱이라 함께
 * 멈추지만, 조용히 꺼진 채 지나가는 대신 배포에서 드러난다.
 *
 * @param schedulingEnabled 정기 실행을 켠다. 시험은 끄고 서비스를 직접 부른다
 * @param cron              정기 실행. 04:00 정합성 검사 뒤에 돈다
 * @param retention         기준 시간 — UNUSED 가 된 뒤(updated_at) 이만큼 지난 사진만 지운다. 방금 올려 아직 저장하지 않은 사진도
 *                          UNUSED 라 시간으로 가른다. 짧을수록 사진을 올려 두고 늦게 저장한 관리자가 400 을 받는 일이 는다
 * @param chunkSize         대상을 한 번에 읽는 건수
 * @param dryRun            지우지 않고 대상만 세어 로그로 남긴다. catalog 가 드로우 생성 시간 예산을 확인했다고 알리면 끈다
 */
@ConfigurationProperties("nova.batch.image-cleanup")
public record ImageCleanupProperties(
        @DefaultValue("true") boolean schedulingEnabled,
        @DefaultValue("0 0 5 * * *") String cron,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("24h") Duration retention,
        @DefaultValue("500") int chunkSize,
        @DefaultValue("true") boolean dryRun
) {

    /**
     * 기준 시간의 하한. 드로우 회차는 증정품 사진 주소를 읽은 뒤 저장할 때까지 행이 없어, 그동안은 "드로우가 가리키는가" 확인에 걸리지 않는다.
     * 기준 시간이 그 시간(catalog 가 묶는다, 예: 60초)보다 충분히 길어야 저장 중인 회차의 사진을 지우지 않는다.
     * 설정으로 바꿀 수 없게 코드에 둔다.
     */
    static final Duration MIN_RETENTION = Duration.ofHours(1);

    public ImageCleanupProperties {
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("nova.batch.image-cleanup.cron 이 cron 식이 아니다: " + cron);
        }
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalArgumentException("nova.batch.image-cleanup.retention 은 1시간 이상이어야 한다: " + retention);
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("nova.batch.image-cleanup.chunk-size 는 1 이상이어야 한다");
        }
    }
}
