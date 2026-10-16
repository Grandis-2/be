package com.grandis.nova.catalog.image;

/** 사진을 어디에 쓰는지. 객체 키 접두가 용도를 말하고, 상품 문서는 products/ · 홍보는 promotions/ 키만 붙인다. */
public enum ImagePurpose {

    PRODUCT("products/"),
    PROMOTION("promotions/");

    private final String keyPrefix;

    ImagePurpose(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /** 객체 키 접두. 끝이 "/" 다. */
    public String keyPrefix() {
        return keyPrefix;
    }
}
