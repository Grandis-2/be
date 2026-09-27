package com.grandis.nova.member.customer;

/** GET/PUT /me/default-address 응답 data. 미등록이면 shippingAddress 가 null 이다(api-spec "미등록이면 shippingAddress=null"). */
public record DefaultAddressResponse(ShippingAddress shippingAddress) {
}
