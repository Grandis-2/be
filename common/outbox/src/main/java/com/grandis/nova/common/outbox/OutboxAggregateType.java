package com.grandis.nova.common.outbox;

/** 소비자가 원장을 다시 읽을 대상. 서비스의 enum 이 구현하고, 표의 aggregate_type 에 이름 그대로 들어간다(30자 이하). */
public interface OutboxAggregateType {

    String name();
}
