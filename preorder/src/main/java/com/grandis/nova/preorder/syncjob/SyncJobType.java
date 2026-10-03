package com.grandis.nova.preorder.syncjob;

/** 외부 예약 시스템에 보내는 일. 예약마다 종류별로 1건까지(uq_sync_job_type). */
public enum SyncJobType {
    REGISTER,
    CANCEL
}
