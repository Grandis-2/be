package com.grandis.nova.preorder.syncjob;

import java.util.List;

/** 관리자 화면의 작업 하나. 공개 예약 id 와 시도 기록을 붙인다. */
record SyncJobView(PreorderSyncJob job, String preorderToken, List<SyncAttempt> attempts) {

    public String lastErrorCode() {
        return attempts.isEmpty() ? null : attempts.getLast().errorCode();
    }
}
