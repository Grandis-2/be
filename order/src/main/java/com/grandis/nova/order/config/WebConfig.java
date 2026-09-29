package com.grandis.nova.order.config;

import com.grandis.nova.order.web.CurrentViewerArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * {@link com.grandis.nova.order.web.CurrentViewer} 리졸버 등록. @CurrentCustomerId 리졸버는 common:security 의
 * AuthWebConfiguration 이 등록한다 — 여기서 또 등록하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentViewerArgumentResolver());
    }
}
