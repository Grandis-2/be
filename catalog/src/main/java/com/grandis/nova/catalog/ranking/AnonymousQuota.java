package com.grandis.nova.catalog.ranking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * IP 하나(IPv6 는 /64 하나)가 하루(KST)에 쓸 수 있는 익명 id 수의 상한(2026-10-18 결정). 익명 id 는 브라우저가 정하는 값이라, 상한이 없으면 id 를 바꿔 가며 아무 상품 ·
 * 검색어나 1위로 올릴 수 있고 본 사람 집합이 끝없이 커진다. 조회 신호와 검색 신호가 같은 상한을 쓴다(같은 브라우저는 같은 익명 id 를 보낸다).
 * 회원은 상한 없이 센다. 사무실처럼 한 IP 를 나눠 쓰는 곳은 그날 상한을 넘은 새 익명 id 를 세지 않는다.
 *
 * IPv6 는 단말 하나가 보통 /64 를 받아 그 안에서 주소를 바꿀 수 있으므로 /64 로 접어 센다. 거절은 지표({@value #REJECTED})로 센다 — 통신사 CGNAT ·
 * 사무실처럼 IP 를 나눠 쓰는 곳에서 정상 비회원이 얼마나 빠지는지 운영에서 본다.
 *
 * 키: {@code catalog:signals:anon:{KST yyyyMMdd}:{IP 또는 IPv6 /64}}(집합, 멤버 = 익명 id). 상한을 넘는 새 id 는 넣었다가 바로 뺀다 — 키 하나의 집합은
 * 상한보다 커지지 않는다(키 수는 IP · /64 수만큼이다).
 */
@Component
public class AnonymousQuota {

    static final String PREFIX = "catalog:signals:anon:";
    static final String REJECTED = "catalog.signals.anonymous.rejected";
    static final Duration TTL = Duration.ofDays(2);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 반환: 받아들이면 1(이미 있던 id 이거나 상한 안의 새 id), 아니면 0. */
    private static final RedisScript<Long> ADMIT = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 0 then
              return 1
            end
            if redis.call('SCARD', KEYS[1]) > tonumber(ARGV[2]) then
              redis.call('SREM', KEYS[1], ARGV[1])
              return 0
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int perIp;
    private final Counter rejected;

    public AnonymousQuota(StringRedisTemplate redis, MeterRegistry meters, @Value("${nova.signals.anonymous-ids-per-ip:20}") int perIp) {
        if (perIp < 1) {
            throw new IllegalArgumentException("nova.signals.anonymous-ids-per-ip must be >= 1, was " + perIp);
        }
        this.redis = redis;
        this.perIp = perIp;
        this.rejected = Counter.builder(REJECTED).description("IP 별 하루 익명 id 상한으로 세지 않은 신호").register(meters);
    }

    /** @return 이 사람의 신호를 세도 되는가. 회원이면 늘 true */
    public boolean admit(ProductViewer viewer, Instant now) {
        if (viewer.member()) {
            return true;
        }
        String key = PREFIX + LocalDate.ofInstant(now, ProductViewCounter.KOREA).format(DAY) + ":" + network(viewer.ip());
        Long admitted = redis.execute(ADMIT, List.of(key), viewer.id().toString(), String.valueOf(perIp), String.valueOf(TTL.toMillis()));
        boolean ok = admitted != null && admitted == 1;
        if (!ok) {
            rejected.increment();
        }
        return ok;
    }

    /**
     * 상한을 세는 단위 — IPv4 는 주소 그대로, IPv6 는 앞 64비트(예: 2001:db8:0:1::/64). IP 모양이 아니면 원문 그대로다(DNS 를 찾지 않는다).
     */
    static String network(String ip) {
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            String literal = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
            int zone = literal.indexOf('%');
            byte[] address = InetAddress.getByName(zone < 0 ? literal : literal.substring(0, zone)).getAddress();
            if (address.length != 16) {
                return ip;
            }
            byte[] prefix = new byte[16];
            System.arraycopy(address, 0, prefix, 0, 8);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
