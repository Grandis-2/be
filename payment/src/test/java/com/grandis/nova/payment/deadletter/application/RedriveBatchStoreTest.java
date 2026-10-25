package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RedriveBatchStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void 진행을_읽지_못하면_503_이다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        given(redis.opsForHash()).willReturn(hash);
        given(hash.entries(anyString())).willThrow(new RedisConnectionFailureException("down"));
        RedriveBatchStore store = new RedriveBatchStore(redis, new RedriveBatchProperties(Duration.ofHours(24)));

        assertThatThrownBy(() -> store.find(UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
