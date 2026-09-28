package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.preorder.preorder.PreorderStatus;

/** 일괄 재처리 후보 한 줄. 재처리 판정에 필요한 값만 읽는다. */
record ReprocessCandidate(Long syncJobId, SyncJobType jobType, SyncJobStatus status,
                                 PreorderStatus preorderStatus, String lastErrorCode) {

    public boolean reprocessable() {
        return SyncJobReprocessor.blocker(jobType, status, preorderStatus).isEmpty();
    }
}
