package com.grandis.nova.worker.job;

/** preorder 가 보내는 작업 메시지의 종류. 종류 하나가 큐 하나다. */
public enum JobEventType {
    REGISTER_JOB_READY("preorder-register", JobType.REGISTER),
    CANCEL_JOB_READY("preorder-cancel", JobType.CANCEL);

    private final String queue;
    private final JobType jobType;

    JobEventType(String queue, JobType jobType) {
        this.queue = queue;
        this.jobType = jobType;
    }

    public String queue() {
        return queue;
    }

    public JobType jobType() {
        return jobType;
    }
}
