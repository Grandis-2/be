package com.grandis.nova.common;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.UUID;

/**
 * UUID 와 BINARY(16) 칸 사이의 변환과 정렬. 변환은 JPA 를 거치지 않는 JDBC 쿼리가 쓴다 — MySQL 드라이버는 UUID 를 그대로
 * 바인딩하지 못한다. Hibernate(JPQL · native {@code @Param})는 같은 바이트로 알아서 바인딩한다.
 *
 * 바이트 순서는 상위 64비트 → 하위 64비트로, Hibernate 가 적는 순서이자 MySQL {@code UUID_TO_BIN(u)} · {@code BIN_TO_UUID(b)} 와 같다.
 * swap 플래그를 쓰지 않는다 — v7 은 이미 시각이 앞에 있다.
 */
public final class UuidBinary {

    /**
     * DB 가 BINARY(16) 을 정렬하는 순서(바이트를 부호 없이 앞에서부터). 잠금 순서처럼 DB 와 같은 순서가 필요할 때 쓴다.
     * {@link UUID#compareTo} 는 부호 있는 비교라 최상위 비트가 1 인 id 에서 DB 와 어긋난다.
     */
    public static final Comparator<UUID> BYTE_ORDER =
            Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
                    .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private static final int LENGTH = 16;

    private UuidBinary() {
    }

    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ByteBuffer.allocate(LENGTH)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("UUID 칸은 16바이트여야 한다: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
