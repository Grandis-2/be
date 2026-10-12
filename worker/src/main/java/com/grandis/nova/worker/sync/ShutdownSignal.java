package com.grandis.nova.worker.sync;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 내려가는 중인가. 컨텍스트가 닫히기 시작하면 켜진다 — 소비기가 멈추기 전이라, 받고 있던 롱 폴링이 그 뒤에 돌려준 메시지를
 * 새로 부르지 않고 큐로 돌려줄 수 있다. 한 번 켜지면 꺼지지 않는다.
 */
final class ShutdownSignal implements ApplicationListener<ContextClosedEvent> {

    private final ApplicationContext context;
    private volatile boolean stopping;

    ShutdownSignal(ApplicationContext context) {
        this.context = context;
    }

    /** 자식 컨텍스트가 닫히는 것은 내려가는 것이 아니다. */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == context) {
            stopping = true;
        }
    }

    boolean stopping() {
        return stopping;
    }
}
