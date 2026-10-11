package com.grandis.nova.preorder.campaign;

import java.time.Instant;
import java.util.UUID;

/** 모집 일정. [opensAt, closesAt) 동안 접수한다. */
public record CampaignSchedule(UUID productId, Instant opensAt, Instant closesAt) {
}
