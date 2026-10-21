package com.grandis.nova.preorder.scaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableDimension;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ServiceNamespace;

/**
 * Application Auto Scaling 의 ECS 서비스 최소 대수를 바꾼다. 대수를 직접 정하지 않는다 — 최소가 지금 대수보다 높으면 AWS 가 늘리고,
 * 이미 더 많으면 그대로 둔다. 내리면 오토스케일링이 부하를 보고 줄인다.
 */
class AwsCapacityFloors implements CapacityFloors {

    private static final Logger log = LoggerFactory.getLogger(AwsCapacityFloors.class);

    private final ApplicationAutoScalingClient client;
    private final String cluster;

    AwsCapacityFloors(ApplicationAutoScalingClient client, String cluster) {
        this.client = client;
        this.cluster = cluster;
    }

    @Override
    public boolean ensure(String service, int min) {
        String resourceId = "service/" + cluster + "/" + service;
        ScalableTarget target = client.describeScalableTargets(request -> request
                        .serviceNamespace(ServiceNamespace.ECS)
                        .scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
                        .resourceIds(resourceId))
                .scalableTargets().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("오토스케일링 대상이 없다 resourceId=" + resourceId));
        if (target.minCapacity() == min) {
            return false;
        }
        client.registerScalableTarget(request -> request
                .serviceNamespace(ServiceNamespace.ECS)
                .scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
                .resourceId(resourceId)
                .minCapacity(min));
        log.info("최소 대수를 바꿨다 service={} {} → {}", service, target.minCapacity(), min);
        return true;
    }
}
