package com.grandis.nova.common.outbox;

import java.time.Duration;

/**
 * 메시지를 밖으로 보내는 통로. 구현만 바꾸면 SQS · HTTP · gRPC 로 나간다. nova.outbox.transport 로 하나를 고른다 —
 * 고르지 않으면 구현이 없어 기동이 실패한다.
 *
 * 실패는 예외로 알린다(릴레이가 다시 보낸다). 같은 메시지가 두 번 갈 수 있다.
 * 구현은 제한 시간을 두고 {@link #maxSendTime()} 으로 알린다. 릴레이 리스(nova.outbox.relay-lease)가 그보다 길지 않으면
 * 보내는 동안 리스가 끝나 다른 인스턴스가 같은 행을 또 보내므로 기동하지 않는다.
 */
public interface MessageTransport {

    void send(OutboundMessage message);

    /** send 한 번이 걸릴 수 있는 최대 시간. 제한 시간이 없는 구현(로그)은 0. */
    default Duration maxSendTime() {
        return Duration.ZERO;
    }
}
