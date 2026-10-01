package com.grandis.nova.waitingroom.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Reactor 컨텍스트의 traceId 를 로그 MDC 로 옮긴다. 한 요청이 여러 스레드를 오가므로 ThreadLocal 에 한 번 넣어서는 남지 않는다.
 * META-INF/services 로 등록해 ContextRegistry 가 읽는다(spring.reactor.context-propagation=auto 와 함께).
 */
public final class TraceIdMdcAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return RequestIdFilter.TRACE_ID_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(RequestIdFilter.TRACE_ID_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(RequestIdFilter.TRACE_ID_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(RequestIdFilter.TRACE_ID_KEY);
    }
}
