package com.grandis.nova.worker.job;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 작업 메시지의 payload. worker 는 DB 를 읽지 않으므로 외부에 보낼 본문이 함께 온다.
 *
 * @param request 외부 mock 에 보낼 요청 본문. preorder 가 작업을 만들 때 정한 그대로다
 */
public record JobReady(UUID syncJobId, String preorderId, JobType jobType, JsonNode request) {
}
