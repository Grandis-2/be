package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.syncjob.application.SyncJobAdminService;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 예약의 등록 작업 재처리(모듈 공개 API). 관리자 화면의 다른 기능(정합성 조치)이 작업 id 대신 예약으로 부른다.
 * 호출한 쪽의 트랜잭션에 묶인다 — 재처리가 거절되면 호출한 쪽이 같은 트랜잭션에 남긴 기록도 함께 되돌아간다.
 */
@Service
public class SyncJobReprocessing {

    private final PreorderSyncJobRepository syncJobs;
    private final SyncJobAdminService adminService;

    SyncJobReprocessing(PreorderSyncJobRepository syncJobs, SyncJobAdminService adminService) {
        this.syncJobs = syncJobs;
        this.adminService = adminService;
    }

    /** @throws BusinessException SYNC_JOB_NOT_FOUND(등록 작업 없음) · SYNC_JOB_NOT_REPROCESSABLE(DEAD_LETTER 가 아님 · 취소 중 예약) */
    @Transactional
    public void reprocessRegister(UUID preorderId, String requestedBy) {
        UUID syncJobId = syncJobs.findByPreorderIdAndJobType(preorderId, SyncJobType.REGISTER)
                .map(PreorderSyncJob::getId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        adminService.reprocess(syncJobId, requestedBy);
    }
}
