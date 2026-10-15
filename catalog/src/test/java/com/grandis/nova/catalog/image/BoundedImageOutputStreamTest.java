package com.grandis.nova.catalog.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("다시 인코딩 출력 — 상한을 넘는 쓰기는 그 자리에서 멈추고, 되감아 고쳐 쓴다")
class BoundedImageOutputStreamTest {

    @Test
    @DisplayName("상한까지는 쓰고(대조군), 한 바이트라도 넘으면 TooLarge — 한 번에 넘는 쓰기 · 한 바이트씩 넘는 쓰기 모두")
    void stopsAtTheLimit() throws Exception {
        try (BoundedImageOutputStream out = new BoundedImageOutputStream(100)) {
            out.write(new byte[100]);
            assertThat(out.length()).isEqualTo(100);
            assertThatThrownBy(() -> out.write(1)).isInstanceOf(BoundedImageOutputStream.TooLarge.class);
        }
        try (BoundedImageOutputStream out = new BoundedImageOutputStream(100)) {
            out.write(new byte[60]);
            assertThatThrownBy(() -> out.write(new byte[41])).isInstanceOf(BoundedImageOutputStream.TooLarge.class);
        }
    }

    @Test
    @DisplayName("앞으로 되감아 고쳐 써도 길이는 늘지 않고, 읽으면 고친 값이다(PNG 라이터가 청크 길이를 이렇게 쓴다)")
    void seekAndPatch() throws Exception {
        try (BoundedImageOutputStream out = new BoundedImageOutputStream(1 << 20)) {
            out.write(new byte[]{1, 2, 3, 4, 5});
            out.seek(1);
            out.write(new byte[]{9, 9});
            assertThat(out.length()).isEqualTo(5);
            assertThat(out.toByteArray()).containsExactly(1, 9, 9, 4, 5);
            out.seek(0);
            assertThat(out.read()).isEqualTo(1);
            byte[] rest = new byte[10];
            assertThat(out.read(rest, 0, 10)).isEqualTo(4);
            assertThat(out.read()).isEqualTo(-1);
        }
    }
}
