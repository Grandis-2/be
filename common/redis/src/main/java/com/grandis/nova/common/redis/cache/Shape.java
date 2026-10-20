package com.grandis.nova.common.redis.cache;

import tools.jackson.databind.JavaType;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 캐시 값 타입의 모양 지문. 레코드는 칸 이름 · 칸 타입을, enum 은 상수 이름을, 제네릭은 타입 인자까지 따라가 이은 글을 SHA-256 으로 줄인다.
 * 칸을 더하거나 빼거나 이름 · 타입을 바꾸면 지문이 바뀌어 새 키를 쓴다 — 옛 버전이 쓴 값을 새 버전이 읽어 새 칸을 null 로 내보내지 않게.
 * 레코드 · enum 이 아닌 타입(String · BigDecimal · Instant · UUID · 컬렉션 등)은 이름만 본다.
 */
final class Shape {

    private Shape() {
    }

    static String fingerprint(JavaType type) {
        String shape = describe(type.getRawClass(), typeArguments(type), new HashSet<>());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 타입을 글로 적는다. 레코드 · enum 이 자기 자신을 다시 만나면(순환) 이름만 적는다. */
    static String describe(Type type, Set<Class<?>> visiting) {
        return switch (type) {
            case Class<?> raw -> describe(raw, new Type[0], visiting);
            case ParameterizedType parameterized -> describe((Class<?>) parameterized.getRawType(),
                    parameterized.getActualTypeArguments(), visiting);
            case GenericArrayType array -> describe(array.getGenericComponentType(), visiting) + "[]";
            case WildcardType wildcard -> "?" + Arrays.stream(wildcard.getUpperBounds())
                    .map(bound -> describe(bound, visiting)).collect(Collectors.joining("&"));
            case TypeVariable<?> variable -> variable.getName();
            default -> type.getTypeName();
        };
    }

    private static String describe(Class<?> raw, Type[] arguments, Set<Class<?>> visiting) {
        StringBuilder out = new StringBuilder(raw.getName());
        if (arguments.length > 0) {
            out.append(Arrays.stream(arguments).map(argument -> describe(argument, visiting))
                    .collect(Collectors.joining(",", "<", ">")));
        }
        if (!visiting.add(raw)) {
            return out.toString();
        }
        try {
            if (raw.isRecord()) {
                Map<String, Type> bound = bindings(raw, arguments);
                out.append(Arrays.stream(raw.getRecordComponents())
                        .map(component -> component.getName() + ":" + describe(resolve(component, bound), visiting))
                        .collect(Collectors.joining(",", "{", "}")));
            } else if (raw.isEnum()) {
                out.append(Arrays.stream(raw.getEnumConstants()).map(constant -> ((Enum<?>) constant).name())
                        .collect(Collectors.joining(",", "[", "]")));
            }
            return out.toString();
        } finally {
            visiting.remove(raw);
        }
    }

    private static Type[] typeArguments(JavaType type) {
        Type[] arguments = new Type[type.containedTypeCount()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = toType(type.containedType(i));
        }
        return arguments;
    }

    /** Jackson 의 타입을 reflect 타입으로 — 타입 인자가 있으면 그것까지. */
    private static Type toType(JavaType type) {
        if (type.containedTypeCount() == 0) {
            return type.getRawClass();
        }
        Type[] arguments = typeArguments(type);
        Class<?> raw = type.getRawClass();
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return arguments;
            }

            @Override
            public Type getRawType() {
                return raw;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    private static Map<String, Type> bindings(Class<?> raw, Type[] arguments) {
        TypeVariable<?>[] variables = raw.getTypeParameters();
        if (arguments.length != variables.length) {
            return Map.of();
        }
        return java.util.stream.IntStream.range(0, variables.length).boxed()
                .collect(Collectors.toMap(i -> variables[i].getName(), i -> arguments[i]));
    }

    /** 레코드 칸의 타입. 레코드의 타입 변수(OffsetPage&lt;T&gt; 의 T)면 받은 타입 인자로 바꾼다. */
    private static Type resolve(RecordComponent component, Map<String, Type> bound) {
        Type type = component.getGenericType();
        if (type instanceof TypeVariable<?> variable && bound.containsKey(variable.getName())) {
            return bound.get(variable.getName());
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = Arrays.stream(parameterized.getActualTypeArguments())
                    .map(argument -> argument instanceof TypeVariable<?> variable && bound.containsKey(variable.getName())
                            ? bound.get(variable.getName()) : argument)
                    .toArray(Type[]::new);
            Class<?> raw = (Class<?>) parameterized.getRawType();
            return new ParameterizedType() {
                @Override
                public Type[] getActualTypeArguments() {
                    return arguments;
                }

                @Override
                public Type getRawType() {
                    return raw;
                }

                @Override
                public Type getOwnerType() {
                    return null;
                }
            };
        }
        return type;
    }
}
