package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.job.JobType;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

/** 작업 종류(등록 · 취소 · 드로우)마다 따로 센다 — 한 경로의 설정 오류가 다른 큐의 받기까지 멈추지 않게. */
public final class RejectGuards {

    private final Map<JobType, RejectGuard> guards = new EnumMap<>(JobType.class);

    RejectGuards(WorkerProperties.RejectGuard settings, Clock clock) {
        for (JobType type : JobType.values()) {
            guards.put(type, new RejectGuard(type, settings, clock));
        }
    }

    public RejectGuard of(JobType type) {
        return guards.get(type);
    }
}
