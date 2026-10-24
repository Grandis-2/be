package com.grandis.nova.waitingroom.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 운영값 변경 이력의 보존 한도와 조회 쪽 크기. 둘 중 먼저 닿는 한도에서 오래된 것부터 지운다.
 *
 * @param retention   이력을 남기는 기간
 * @param maxEntries  이력을 남기는 최대 건수
 * @param pageSize    조회에서 size 를 빼면 쓰는 건수
 * @param maxPageSize 조회 한 번에 받는 최대 건수
 */
@ConfigurationProperties("waitingroom.admin.change-log")
public record ChangeLogProperties(Duration retention, Long maxEntries, Integer pageSize, Integer maxPageSize) {

    @ConstructorBinding
    public ChangeLogProperties {
        // 행사 뒤 사고를 되짚는 회고가 보통 한 달 안에 끝난다
        retention = retention == null ? Duration.ofDays(30) : retention;
        // 관리자 변경은 하루 수십 건 수준이라 30일치를 넉넉히 담는다. 한 건 100바이트 안팎이라 1MB 남짓이다
        maxEntries = maxEntries == null ? 10_000L : maxEntries;
        pageSize = pageSize == null ? 20 : pageSize;
        maxPageSize = maxPageSize == null ? 100 : maxPageSize;
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("waitingroom.admin.change-log.retention 은 0 보다 커야 한다: " + retention);
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("waitingroom.admin.change-log.max-entries 는 1 이상이어야 한다: " + maxEntries);
        }
        if (maxPageSize < 1 || pageSize < 1 || pageSize > maxPageSize) {
            throw new IllegalArgumentException("waitingroom.admin.change-log.page-size(" + pageSize
                    + ") 는 1 이상 max-page-size(" + maxPageSize + ") 이하여야 한다");
        }
    }
}
