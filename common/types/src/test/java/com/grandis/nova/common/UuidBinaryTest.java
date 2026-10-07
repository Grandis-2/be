package com.grandis.nova.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidBinaryTest {

    private static final UUID ID = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d");

    /** 문자열 표기 순서 그대로다 — Hibernate 가 적는 바이트, MySQL UUID_TO_BIN(u) 와 같다. */
    @Test
    void 바이트는_문자열_표기_순서다() {
        assertThat(HexFormat.of().formatHex(UuidBinary.toBytes(ID))).isEqualTo("0199a3f27c4e7a108b2d3f4e5a6b7c8d");
    }

    @Test
    void 왕복하면_같은_UUID() {
        UUID random = UUID.randomUUID();

        assertThat(UuidBinary.fromBytes(UuidBinary.toBytes(random))).isEqualTo(random);
    }

    @Test
    void null_은_null() {
        assertThat(UuidBinary.toBytes(null)).isNull();
        assertThat(UuidBinary.fromBytes(null)).isNull();
    }

    @Test
    void 열여섯_바이트가_아니면_거부한다() {
        assertThatThrownBy(() -> UuidBinary.fromBytes(new byte[8])).isInstanceOf(IllegalArgumentException.class);
    }

    /** 최상위 비트가 1 인 id 는 부호 있는 비교에서 앞에 오지만 DB(바이트 부호 없음)에서는 뒤에 온다. */
    @Test
    void 정렬은_DB_바이트_순서다() {
        UUID low = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d");
        UUID high = UUID.fromString("f199a3f2-7c4e-4a10-8b2d-3f4e5a6b7c8d");
        UUID lowerLsb = UUID.fromString("0199a3f2-7c4e-7a10-0b2d-3f4e5a6b7c8d");

        List<UUID> ids = new ArrayList<>(List.of(high, low, lowerLsb));
        ids.sort(UuidBinary.BYTE_ORDER);

        assertThat(ids).containsExactly(lowerLsb, low, high);
        assertThat(high.compareTo(low)).as("부호 있는 비교는 반대다").isNegative();
    }
}
