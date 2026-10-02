package com.grandis.nova.waitingroom.entry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 진입 · 조회 응답 본문. status 로 다음 행동을 고른다. */
public sealed interface QueueView {

    String status();

    /** 차례가 왔다. 입장권을 X-Admission-Ticket 에 실어 접수한다. */
    record Admitted(String admissionTicket, long expiresIn) implements QueueView {

        @Override
        @JsonProperty
        public String status() {
            return "ADMITTED";
        }
    }

    /**
     * 줄 서는 중. position 은 1부터 세는 내 순서이고 뒤로 가지 않는다. 대기 토큰과 재방문 여부는 진입 응답에만,
     * 총원 · 뒤 인원은 조회 응답에만 있다(줄 서기는 왕복을 늘리지 않으려고 세지 않는다).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Waiting(String queueToken, long position, long etaSeconds, Long totalWaiting, Long behind,
                   Boolean rejoined, @JsonIgnore long retryAfterSeconds)
            implements QueueView {

        @Override
        @JsonProperty
        public String status() {
            return "WAITING";
        }
    }

    /** 줄에 없다. NOT_IN_QUEUE 면 다시 진입하고, SALE_CLOSED 면 끝이다. */
    record Closed(Reason reason) implements QueueView {

        @Override
        @JsonProperty
        public String status() {
            return "CLOSED";
        }

        public enum Reason { NOT_IN_QUEUE, SALE_CLOSED }
    }
}
