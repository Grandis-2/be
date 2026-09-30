package com.grandis.nova.preorder.syncjob.domain;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;

import java.util.Optional;

/** 일괄 재처리 후보 한 줄. 재처리 판정에 필요한 값만 읽는다. */
public record ReprocessCandidate(Long syncJobId, SyncJobType jobType, SyncJobStatus status,
                                 PreorderStatus preorderStatus, String lastErrorCode) {

    public boolean reprocessable() {
        return blocker().isEmpty();
    }

    /** 재처리할 수 없는 까닭. 할 수 있으면 비어 있다. */
    public Optional<String> blocker() {
        if (jobType != SyncJobType.REGISTER || status != SyncJobStatus.DEAD_LETTER) {
            return Optional.of("jobType=%s, status=%s".formatted(jobType, status));
        }
        if (preorderStatus == PreorderStatus.CANCELING || preorderStatus == PreorderStatus.CANCELED) {
            return Optional.of("preorderStatus=" + preorderStatus);
        }
        return Optional.empty();
    }
}
