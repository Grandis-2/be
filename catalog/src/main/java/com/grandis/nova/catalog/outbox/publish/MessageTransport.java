package com.grandis.nova.catalog.outbox.publish;

/**
 * 메시지를 밖으로 보내는 통로. 구현만 바꾸면 SQS 나 로그로 나간다.
 * 실패는 예외로 알린다(릴레이가 다시 보낸다). 같은 메시지가 두 번 갈 수 있다. 구현은 제한 시간을 둔다.
 */
public interface MessageTransport {

    void send(OutboundMessage message);
}
