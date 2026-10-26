package com.grandis.nova.preorder.scaling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 회차 일정에 맞춘 최소 대수({@link CampaignScalingReconciler}).
 *
 * @param enabled     AWS 에 실제로 넣는다. 기본은 꺼짐 — 꺼진 동안은 계산한 값이 바뀔 때 로그만 남긴다.
 *                    preorder 태스크 역할의 권한과 Terraform 의 최소 대수 무시 설정이 생긴 뒤 켠다
 * @param interval    다시 맞추는 주기. 기본 1분 — 오픈 전 여유(15분)와 증설에 걸리는 시간(수 분) 안에 몇 번 놓쳐도 늦지 않다
 * @param region      ECS 서비스가 있는 리전
 * @param cluster     ECS 클러스터 이름(운영은 nova-prod). 환경마다 달라 기본값이 없고, 켜려면 넣어야 한다
 * @param leadTime    오픈 이만큼 전부터 올린다. 기본 15분 — 일정은 오픈 10분 전까지 바뀌고, 그 뒤 태스크가 뜨고 데워질 시간이 남는다
 * @param holdAfterOpen 오픈 뒤 이만큼 유지한다. 기본 30분 — 몰림은 오픈 직후이고, 그 뒤는 오토스케일링에 맡긴다
 * @param lockTtl     태스크 사이 잠금의 수명. 기본 1분 — 한 번 맞추는 데 AWS 호출 넷(호출마다 제한 5초)이라 넉넉하고,
 *                    잠금을 쥔 태스크가 죽어도 다음 주기 안에 풀린다
 */
@ConfigurationProperties("nova.scaling")
record ScalingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("ap-northeast-2") String region,
        @DefaultValue("") String cluster,
        @DefaultValue("15m") Duration leadTime,
        @DefaultValue("30m") Duration holdAfterOpen,
        @DefaultValue("1m") Duration lockTtl,
        @DefaultValue Waitingroom waitingroom,
        @DefaultValue Preorder preorder
) {

    ScalingProperties {
        if (enabled && (cluster.isBlank() || waitingroom.service().isBlank() || preorder.service().isBlank())) {
            throw new IllegalArgumentException(
                    "nova.scaling.cluster · waitingroom.service · preorder.service 를 넣어야 켤 수 있다");
        }
        if (!interval.isPositive() || !lockTtl.isPositive() || leadTime.isNegative() || holdAfterOpen.isNegative()) {
            throw new IllegalArgumentException(
                    "nova.scaling 의 interval · lock-ttl 은 0 보다, lead-time · hold-after-open 은 0 이상이어야 한다");
        }
    }

    /**
     * @param service         ECS 서비스 이름(운영은 nova-prod-waitingroom). 환경마다 달라 기본값이 없다
     * @param baseline        회차가 없을 때의 최소 대수. Terraform 의 min 과 같게 둔다
     * @param max             최대 대수. Terraform 의 max 와 같게 둔다 — 최소를 이보다 높이면 AWS 가 거절한다
     * @param visitorsPerTask 태스크 하나가 맡는 예상 방문자 수. 기본 5,000 — 대기 중 폴링은 대부분 10~30초 주기라 1인당 약 0.1건/초,
     *                        태스크 하나가 약 500건/초를 받는다고 본 값이다. 측정 전 임시값이다
     * @param surge           회차가 열릴 때의 최소 대수(baseline 보다 작으면 baseline). 기본 3 — 예상 방문자 수를 넣지 않은
     *                        회차도 오픈 직후 몰림을 1대가 받지 않게
     */
    record Waitingroom(@DefaultValue("") String service, @DefaultValue("1") int baseline,
                       @DefaultValue("5") int max, @DefaultValue("5000") int visitorsPerTask,
                       @DefaultValue("3") int surge) {

        Waitingroom {
            requireRange("waitingroom", baseline, max);
            if (visitorsPerTask < 1) {
                throw new IllegalArgumentException("nova.scaling.waitingroom.visitors-per-task 는 1 이상이어야 한다");
            }
            if (surge < 1) {
                throw new IllegalArgumentException("nova.scaling.waitingroom.surge 는 1 이상이어야 한다");
            }
        }
    }

    /**
     * @param service ECS 서비스 이름(운영은 nova-prod-preorder). 환경마다 달라 기본값이 없다
     * @param surge   회차가 열릴 때의 최소 대수. 기본 3 — 같은 회차의 접수는 회차 행 잠금 하나에 줄 서서, 측정에서 3대보다 늘려도
     *                접수 상한이 같았다. 예상 방문자 수와 무관하다
     */
    record Preorder(@DefaultValue("") String service, @DefaultValue("1") int baseline,
                    @DefaultValue("5") int max, @DefaultValue("3") int surge) {

        Preorder {
            requireRange("preorder", baseline, max);
            if (surge < baseline) {
                throw new IllegalArgumentException("nova.scaling.preorder.surge 는 baseline 이상이어야 한다");
            }
        }
    }

    private static void requireRange(String name, int baseline, int max) {
        if (baseline < 0 || max < baseline) {
            throw new IllegalArgumentException("nova.scaling." + name + " 은 0 ≤ baseline ≤ max 여야 한다");
        }
    }
}
