package com.grandis.nova.preorder.campaign;

/** 접수에서 발급한 순번과 그 순번이 속한 배송 차수. */
public record IssuedPosition(long position, ShipmentBatchSnapshot batch) {
}
