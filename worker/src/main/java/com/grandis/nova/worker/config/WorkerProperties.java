package com.grandis.nova.worker.config;

import com.grandis.nova.common.sqs.BatchConsumerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * worker 운영값. 기동 때 한 번 읽고, 범위가 어긋나면 기동을 막는다 — 잘못된 값이 부하가 걸릴 때 처음 드러나지 않게.
 * 여기서는 상한만 정하고 그 안의 실제 동시 요청 수는 자동 조절이 정한다. 시작값은 부하 시험으로 다시 정한다.
 */
@ConfigurationProperties("nova.worker")
public record WorkerProperties(
        @DefaultValue Mock mock,
        @DefaultValue Concurrency concurrency,
        @DefaultValue Circuit circuit,
        @DefaultValue Retry retry,
        @DefaultValue Consumer consumer,
        @DefaultValue RejectGuard rejectGuard
) {

    /** SQS DelaySeconds 상한. 지연 재전송의 지연은 이보다 길 수 없다. */
    private static final Duration MAX_DELAY = Duration.ofMinutes(15);

    public WorkerProperties {
        // 한 차례(조회 + 호출 + 즉시 재시도)가 가시성 시간 안에 끝나야 같은 메시지를 다른 worker 가 또 받지 않는다
        Duration round = mock.connectTimeout().plus(mock.readTimeout()).multipliedBy(retry.inRound() + 2L);
        require(consumer.visibility().compareTo(round) > 0,
                "consumer.visibility 는 한 차례(조회 + 호출 + 즉시 재시도)보다 길어야 한다: " + consumer.visibility());
    }

    /**
     * @param connectionMaxLifetime 연결 하나를 다시 쓰는 최대 시간. 짧게 두어 새로 뜬 mock 에도 요청이 가게 한다
     * @param dnsCache              JVM 이 이름 풀이 결과를 기억하는 시간. 길면 사라진 mock 주소로 계속 보낸다
     * @param slowCallThreshold     성공했어도 이보다 느리면 차단기가 느린 호출로 센다
     */
    public record Mock(
            URI baseUrl,
            @DefaultValue("30s") Duration connectionMaxLifetime,
            @DefaultValue("5s") Duration dnsCache,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout,
            @DefaultValue("1500ms") Duration slowCallThreshold
    ) {

        public Mock {
            require(baseUrl != null && baseUrl.isAbsolute(), "mock.base-url 은 절대 주소여야 한다: " + baseUrl);
            require(positive(connectionMaxLifetime) && positive(dnsCache) && positive(connectTimeout)
                    && positive(readTimeout), "mock 의 시간 값은 0 보다 커야 한다");
            require(positive(slowCallThreshold) && slowCallThreshold.compareTo(readTimeout) < 0,
                    "mock.slow-call-threshold 는 0 보다 크고 read-timeout 보다 짧아야 한다: " + slowCallThreshold);
        }
    }

    /**
     * 동시에 나가 있는 요청 수. 상한 = perMock × mockMaxInstances 이고, 자동 조절이 min ~ 상한 사이에서 정한다.
     *
     * @param mockMaxInstances mock 자동 확장의 최대 대수. 인프라가 넘긴다
     * @param permitWait       받은 뒤 자리를 못 얻었을 때 큐로 돌려보내기 전에 기다리는 시간
     * @param healthyP95       이보다 빠르고 실패율이 healthyFailureRate 미만이면 자리를 늘린다
     */
    public record Concurrency(
            @DefaultValue("16") int perMock,
            @DefaultValue("1") int mockMaxInstances,
            @DefaultValue("8") int initial,
            @DefaultValue("2") int min,
            @DefaultValue("1") int cancelReserved,
            @DefaultValue("100ms") Duration permitWait,
            @DefaultValue("40") int ratePerSecond,
            @DefaultValue("10s") Duration adjustEvery,
            @DefaultValue("1s") Duration healthyP95,
            @DefaultValue("5") int healthyFailureRate
    ) {

        public Concurrency {
            require(perMock >= 1 && mockMaxInstances >= 1 && ratePerSecond >= 1,
                    "concurrency 의 per-mock · mock-max-instances · rate-per-second 는 1 이상이어야 한다");
            // 곱이 int 를 넘으면 상한이 0 이나 음수로 접힌다
            require((long) perMock * mockMaxInstances <= Integer.MAX_VALUE
                            && (long) ratePerSecond * mockMaxInstances <= Integer.MAX_VALUE,
                    "concurrency 의 per-mock · rate-per-second 에 mock-max-instances 를 곱한 값이 너무 크다");
            require(min >= 1 && min <= initial && initial <= (long) perMock * mockMaxInstances,
                    "concurrency 는 1 <= min <= initial <= per-mock × mock-max-instances 여야 한다: min=" + min
                            + " initial=" + initial);
            require(cancelReserved >= 0 && cancelReserved < min,
                    "concurrency.cancel-reserved 는 0 이상이고 min 보다 작아야 한다: " + cancelReserved);
            require(positive(permitWait) && positive(adjustEvery) && positive(healthyP95),
                    "concurrency 의 시간 값은 0 보다 커야 한다");
            require(percent(healthyFailureRate), "concurrency.healthy-failure-rate 는 1 ~ 100 이어야 한다");
        }

        /** worker 하나의 동시 요청 상한. */
        public int limit() {
            return perMock * mockMaxInstances;
        }

        /** worker 하나의 초당 요청 상한. */
        public int rateLimit() {
            return ratePerSecond * mockMaxInstances;
        }
    }

    /**
     * @param window        판단에 쓰는 최근 호출 수
     * @param openFirst     처음 열렸을 때 닫아 두는 시간. 다시 열릴 때마다 두 배, openMax 까지
     * @param halfOpenCalls 반쯤 열린 동안 시험으로 보내는 호출 수
     */
    public record Circuit(
            @DefaultValue("20") int window,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("50") int failureRate,
            @DefaultValue("80") int slowCallRate,
            @DefaultValue("10s") Duration openFirst,
            @DefaultValue("2m") Duration openMax,
            @DefaultValue("3") int halfOpenCalls
    ) {

        public Circuit {
            require(minimumCalls >= 1 && minimumCalls <= window,
                    "circuit 는 1 <= minimum-calls <= window 여야 한다: minimum-calls=" + minimumCalls);
            require(percent(failureRate) && percent(slowCallRate),
                    "circuit 의 failure-rate · slow-call-rate 는 1 ~ 100 이어야 한다");
            require(positive(openFirst) && openFirst.compareTo(openMax) <= 0 && openMax.compareTo(MAX_DELAY) <= 0,
                    "circuit 는 0 < open-first <= open-max <= 15m 여야 한다(열린 동안 지연 재전송한다): " + openMax);
            require(halfOpenCalls >= 1, "circuit.half-open-calls 는 1 이상이어야 한다");
        }
    }

    /**
     * @param inRound     한 차례 안에서 곧바로 다시 보내는 횟수(일시 실패만)
     * @param backoff     다음 차례까지의 지연. 차례 사이마다 하나씩이라 roundsPerGeneration − 1 개
     * @param generations REGISTER 가 최종 실패하기까지의 세대 수. CANCEL 은 제한이 없다
     */
    public record Retry(
            @DefaultValue("1") int inRound,
            @DefaultValue({"10s", "20s", "40s"}) List<Duration> backoff,
            @DefaultValue("4") int roundsPerGeneration,
            @DefaultValue("3") int generations,
            @DefaultValue("15m") Duration generationGap
    ) {

        public Retry {
            backoff = List.copyOf(backoff);
            require(inRound >= 0 && roundsPerGeneration >= 1 && generations >= 1,
                    "retry 는 in-round >= 0, rounds-per-generation >= 1, generations >= 1 이어야 한다");
            require(backoff.size() == roundsPerGeneration - 1,
                    "retry.backoff 는 rounds-per-generation − 1 개여야 한다: " + backoff);
            require(backoff.stream().allMatch(delay -> positive(delay) && delay.compareTo(MAX_DELAY) <= 0)
                            && positive(generationGap) && generationGap.compareTo(MAX_DELAY) <= 0,
                    "retry 의 backoff · generation-gap 은 0 보다 크고 15m 이하여야 한다");
        }
    }

    /**
     * @param waitSeconds 롱 폴링 대기. 받기 멈춤 · 종료는 진행 중인 롱 폴링이 끝난 뒤부터 듣는다. 0 이면 빈 큐를 쉬지 않고 받는다
     * @param visibility  받은 메시지를 다른 worker 에게 숨기는 시간
     * @param stopTimeout 종료 때 호출 중인 건을 기다리는 시간
     */
    public record Consumer(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5") int waitSeconds,
            @DefaultValue("120s") Duration visibility,
            @DefaultValue("20s") Duration stopTimeout
    ) {

        public Consumer {
            require(waitSeconds >= 1, "consumer.wait-seconds 는 1 이상이어야 한다: " + waitSeconds);
            // 범위 검증은 소비 설정이 한다. 큐 이름은 쓰는 쪽이 정하므로 여기서는 자리만 채운다
            new BatchConsumerSettings("queue", waitSeconds, visibility, stopTimeout);
        }

        public BatchConsumerSettings settings(String queue) {
            return new BatchConsumerSettings(queue, waitSeconds, visibility, stopTimeout);
        }
    }

    /**
     * 우리 요청이 잘못돼 거절이 이어질 때 받기를 멈추는 기준.
     *
     * @param invalidRequests per 동안 이만큼 거절되면 멈춘다
     */
    public record RejectGuard(
            @DefaultValue("5") int invalidRequests,
            @DefaultValue("1m") Duration per
    ) {

        public RejectGuard {
            require(invalidRequests >= 1 && positive(per), "reject-guard 는 invalid-requests >= 1, per > 0 이어야 한다");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.isPositive();
    }

    private static boolean percent(int value) {
        return value >= 1 && value <= 100;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("nova.worker 설정: " + message);
        }
    }
}
