package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.common.OffsetPage;

import java.util.List;

/** @param errorGroups 묶어 달라고 하지 않았으면 null */
public record SyncJobPage(OffsetPage<SyncJobView> page, List<ErrorGroup> errorGroups) {
}
