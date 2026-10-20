package com.grandis.nova.catalog.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymousQuotaTest {

    @Test
    @DisplayName("상한 단위 — IPv4 는 주소 그대로, IPv6 는 /64 로 접는다(표기 · 대괄호 · 영역 표시와 상관없이 같은 /64 는 같은 값). IP 가 아니면 원문")
    void networkFoldsIpv6To64() {
        assertThat(AnonymousQuota.network("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(AnonymousQuota.network("2001:db8:0:1::1")).isEqualTo("2001:db8:0:1:0:0:0:0/64");
        assertThat(AnonymousQuota.network("2001:0db8:0000:0001:ffff:ffff:ffff:ffff")).isEqualTo("2001:db8:0:1:0:0:0:0/64");
        assertThat(AnonymousQuota.network("[2001:db8:0:1::15]")).isEqualTo("2001:db8:0:1:0:0:0:0/64");
        assertThat(AnonymousQuota.network("fe80::1%eth0")).isEqualTo("fe80:0:0:0:0:0:0:0/64");
        assertThat(AnonymousQuota.network("2001:db8:0:2::1")).as("다른 /64").isEqualTo("2001:db8:0:2:0:0:0:0/64");
        assertThat(AnonymousQuota.network("::ffff:203.0.113.7")).as("IPv4 대응 주소는 그 IPv4").isEqualTo("203.0.113.7");
        assertThat(AnonymousQuota.network("not:an:ip:zz")).isEqualTo("not:an:ip:zz");
        assertThat(AnonymousQuota.network("localhost:x")).as("이름처럼 보여도 조회하지 않고 원문").isEqualTo("localhost:x");
    }

    @Test
    @DisplayName("상한은 1 이상 — 0 이하면 비회원을 하나도 못 세므로 기동을 막는다")
    void capMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnonymousQuota(null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
