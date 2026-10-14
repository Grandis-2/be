package com.grandis.nova.member.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 회원 조회의 이름 가리기 — 첫 글자 + **, 길이와 상관없이 같은 모양")
class MemberNameTest {

    @Test
    @DisplayName("석 자 · 외자 · 넉 자 · 한 글자 모두 첫 글자 + ** — 이름 길이가 드러나지 않는다")
    void keepsOnlyTheFirstCharacter() {
        assertThat(CustomerService.MemberName.mask("김철수")).isEqualTo("김**");
        assertThat(CustomerService.MemberName.mask("김철")).isEqualTo("김**");
        assertThat(CustomerService.MemberName.mask("남궁민수")).isEqualTo("남**");
        assertThat(CustomerService.MemberName.mask("김")).isEqualTo("김**");
        assertThat(CustomerService.MemberName.mask("Kim Chulsoo")).isEqualTo("K**");
    }

    @Test
    @DisplayName("앞뒤 공백은 지우고 세고, 첫 글자는 코드포인트 하나(이모지를 반으로 자르지 않는다). 비었으면 ***")
    void stripsAndCountsCodePoints() {
        assertThat(CustomerService.MemberName.mask("  이영희 ")).isEqualTo("이**");
        assertThat(CustomerService.MemberName.mask("😀철수")).isEqualTo("😀**");
        assertThat(CustomerService.MemberName.mask("   ")).isEqualTo("***");
        assertThat(CustomerService.MemberName.mask(null)).isEqualTo("***");
    }
}
