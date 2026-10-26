package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ScalingDemand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 관리자 입장 속도를 읽어 preorder 최소 대수에 넣는다. 읽지 못하면 그 주기는 surge 로 맞추고 실패로 센다. */
class CampaignScalingReconcilerTest {

    static final String PREORDER = "nova-prod-preorder";
    static final String WAITINGROOM = "nova-prod-waitingroom";

    final Campaigns campaigns = mock(Campaigns.class);
    final CapacityFloors floors = mock(CapacityFloors.class);
    final ScalingLock lock = mock(ScalingLock.class);
    final AdmissionCredit admissionCredit = mock(AdmissionCredit.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CampaignScalingReconciler reconciler;

    @BeforeEach
    void setUp() {
        ScalingProperties properties = new ScalingProperties(false, Duration.ofMinutes(1), "ap-northeast-2", "",
                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom(WAITINGROOM, 1, 5, 5000, 3),
                new ScalingProperties.Preorder(PREORDER, 1, 5, 3));
        when(lock.tryAcquire()).thenReturn("token");
        reconciler = new CampaignScalingReconciler(campaigns, properties, floors, lock, admissionCredit, 100,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC), meters);
    }

    @AfterEach
    void tearDown() {
        reconciler.stop();
    }

    @Test
    void 회차가_없으면_관리자_입장_속도를_읽지_않고_평소_대수() {
        demand(0);

        reconciler.reconcileExclusively();

        verify(floors).ensure(PREORDER, 1);
        verify(admissionCredit, never()).read();
        assertThat(admissionTarget()).isZero();
    }

    @Test
    void 회차가_있으면_관리자_입장_속도를_받을_대수로_맞추고_지표로_낸다() {
        demand(1);
        when(admissionCredit.read()).thenReturn(OptionalLong.of(450));

        reconciler.reconcileExclusively();

        verify(floors).ensure(PREORDER, 5);
        assertThat(admissionTarget()).isEqualTo(450);
        assertThat(failures()).isZero();
    }

    @Test
    void 관리자_입장_속도가_없으면_surge() {
        demand(1);
        when(admissionCredit.read()).thenReturn(OptionalLong.empty());

        reconciler.reconcileExclusively();

        verify(floors).ensure(PREORDER, 3);
        assertThat(admissionTarget()).isZero();
    }

    /** 읽기 실패로 회차 몫(surge · 대기열)까지 놓치지 않는다 — 다음 주기에 다시 읽는다. */
    @Test
    void 관리자_입장_속도를_읽지_못하면_surge_로_맞추고_실패로_센다() {
        demand(1);
        when(admissionCredit.read()).thenThrow(new RedisConnectionFailureException("down"));

        reconciler.reconcileExclusively();

        verify(floors).ensure(PREORDER, 3);
        verify(floors).ensure(WAITINGROOM, 3);
        assertThat(failures()).isEqualTo(1);
        assertThat(admissionTarget()).isZero();
    }

    private void demand(long count) {
        when(campaigns.findScalingDemand(any(), any(), any())).thenReturn(new ScalingDemand(count, null));
    }

    private double admissionTarget() {
        return meters.get(CampaignScalingReconciler.ADMISSION_TARGET_METRIC).gauge().value();
    }

    private double failures() {
        return meters.get(CampaignScalingReconciler.FAILURE_METRIC).counter().count();
    }
}
