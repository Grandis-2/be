package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/** 접수 202 응답. 사용자 · 관리자 접수가 같은 모양이다. */
final class AcceptResponses {

    static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private AcceptResponses() {
    }

    static ResponseEntity<ApiResponse<PreorderAcceptedResponse>> accepted(AcceptResult result) {
        PreorderAcceptedResponse body = PreorderAcceptedResponse.from(result);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(body.statusUrl()))
                .header(IDEMPOTENT_REPLAY_HEADER, String.valueOf(result.replayed()))
                .body(ApiResponse.ok(body));
    }
}
