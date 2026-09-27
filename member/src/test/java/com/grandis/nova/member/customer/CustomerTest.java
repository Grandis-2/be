package com.grandis.nova.member.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Customer.fromKakao — display_name NOT NULL varchar(100)")
class CustomerTest {

    @Test
    @DisplayName("닉네임 동의가 없으면(null·공백) '카카오 회원'")
    void nullOrBlankNicknameFallsBack() {
        assertThat(Customer.fromKakao("1", null).getDisplayName()).isEqualTo("카카오 회원");
        assertThat(Customer.fromKakao("1", "   ").getDisplayName()).isEqualTo("카카오 회원");
        assertThat(Customer.fromKakao("1", " 홍길동 ").getDisplayName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("101자는 앞 100자로 잘리고, 100자는 그대로")
    void truncatesTo100() {
        String hundred = "가".repeat(100);
        assertThat(Customer.fromKakao("1", hundred).getDisplayName()).isEqualTo(hundred);
        assertThat(Customer.fromKakao("1", hundred + "나").getDisplayName()).isEqualTo(hundred);
    }

    @Test
    @DisplayName("NFD 닉네임은 NFC 로 저장되고, NFC 기준 100자로 자른다")
    void nfdNicknameIsNormalized() {
        String nfc = "한국어이름".repeat(20);   // 100 코드포인트
        String nfd = java.text.Normalizer.normalize(nfc + "한", java.text.Normalizer.Form.NFD);
        String stored = Customer.fromKakao("1", nfd).getDisplayName();
        assertThat(stored).isEqualTo(nfc);
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(100);
    }

    @Test
    @DisplayName("이모지가 100번째 경계에 걸려도 반쪽이 남지 않는다 (코드포인트 기준)")
    void doesNotSplitSurrogatePair() {
        String emoji = "\uD83D\uDE00";   // 😀 — char 2개, 코드포인트 1개
        String name = "가".repeat(99) + emoji + "나";   // 코드포인트 101, char 102

        String cut = Customer.fromKakao("1", name).getDisplayName();

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(100);
        assertThat(cut).endsWith(emoji);
        // char 기준으로 잘랐다면 마지막 char 가 홀로 남은 상위 서러게이트다
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }
}
