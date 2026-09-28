package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 취소 시작은 CANCELING 으로 바꾸는 것까지라 202 다. 이후는 비동기로 이어진다. */
final class CancelResponses {

    private CancelResponses() {
    }

    static ResponseEntity<ApiResponse<CancelResult>> accepted(CancelResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }
}
