package com.grandis.nova.common;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
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
}
