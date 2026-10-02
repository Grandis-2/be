package com.grandis.nova.preorder.outbox.publish;

/**
 * 메시지를 밖으로 보내는 통로. 구현만 바꾸면 SQS · HTTP · gRPC 로 나간다.
 * 실패는 예외로 알린다(릴레이가 다시 보낸다). 같은 메시지가 두 번 갈 수 있다.
 * 구현은 제한 시간을 둔다 — 릴레이가 보내는 동안 행 잠금을 쥔다.
 */
public interface MessageTransport {

    void send(OutboundMessage message);
}
