package com.grandis.nova.member.customer;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열 길이를 UTF-16 단위가 아니라 **코드포인트** 로 센다. `@Size` 는 char 를 세서 이모지 50개(char 100)를 varchar(50) 한도 초과로 거절하는데,
 * MySQL utf8mb4 varchar(N) 은 문자(코드포인트) N개다. `Customer` 의 절단(truncateToCodePoints)과 같은 자(尺)를 쓴다. null 은 통과(필수 여부는 @NotBlank 몫).
 * CodeRabbit 지적. 실측: DefaultAddressIntegrationTest.supplementaryCharactersCountAsOne.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CodePointSizeValidator.class)
public @interface CodePointSize {

    int max();

    String message() default "length must be at most {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
