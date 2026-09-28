package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.OffsetPage;

import java.util.List;

/** @param errorGroups 묶어 달라고 하지 않았으면 null */
record SyncJobPage(OffsetPage<SyncJobView> page, List<ErrorGroup> errorGroups) {
}
