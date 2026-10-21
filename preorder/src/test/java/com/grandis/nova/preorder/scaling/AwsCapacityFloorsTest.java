package com.grandis.nova.preorder.scaling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.RegisterScalableTargetRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.RegisterScalableTargetResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableDimension;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/** 최소 대수만 바꾸고(대수 · 최대는 건드리지 않는다), 같으면 부르지 않는다. */
class AwsCapacityFloorsTest {

    ApplicationAutoScalingClient client;
    AwsCapacityFloors floors;

    @BeforeEach
    void setUp() {
        // 요청 객체를 받는 메서드만 막고, 빌더를 받는 편의 메서드는 그대로 둔다
        client = mock(ApplicationAutoScalingClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(RegisterScalableTargetResponse.builder().build())
                .when(client).registerScalableTarget(any(RegisterScalableTargetRequest.class));
        floors = new AwsCapacityFloors(client, "nova");
    }

    @Test
    void 지금_최소와_다르면_최소만_바꾼다() {
        currentMin(1);

        assertThat(floors.ensure("waitingroom", 4)).isTrue();

        ArgumentCaptor<RegisterScalableTargetRequest> request =
                ArgumentCaptor.forClass(RegisterScalableTargetRequest.class);
        verify(client).registerScalableTarget(request.capture());
        assertThat(request.getValue().resourceId()).isEqualTo("service/nova/waitingroom");
        assertThat(request.getValue().scalableDimension()).isEqualTo(ScalableDimension.ECS_SERVICE_DESIRED_COUNT);
        assertThat(request.getValue().minCapacity()).isEqualTo(4);
        assertThat(request.getValue().maxCapacity()).isNull();
    }

    @Test
    void 같으면_부르지_않는다() {
        currentMin(4);

        assertThat(floors.ensure("waitingroom", 4)).isFalse();

        verify(client, never()).registerScalableTarget(any(RegisterScalableTargetRequest.class));
    }

    @Test
    void 오토스케일링_대상이_없으면_실패한다() {
        doReturn(DescribeScalableTargetsResponse.builder().build())
                .when(client).describeScalableTargets(any(DescribeScalableTargetsRequest.class));

        assertThatThrownBy(() -> floors.ensure("waitingroom", 4)).isInstanceOf(IllegalStateException.class);
    }

    private void currentMin(int min) {
        doReturn(DescribeScalableTargetsResponse.builder()
                .scalableTargets(ScalableTarget.builder().resourceId("service/nova/waitingroom").minCapacity(min)
                        .maxCapacity(10).build())
                .build())
                .when(client).describeScalableTargets(any(DescribeScalableTargetsRequest.class));
    }
}
