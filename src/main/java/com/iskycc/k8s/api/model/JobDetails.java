package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

/** Job 的执行状态与 Pod 模板。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class JobDetails extends WorkloadDetails {
    public JobDetails(JsonObject resource) { super(resource, "batch/v1", "Job"); }

    public Integer getParallelism() { return fields.integer("spec", "parallelism"); }
    public Integer getCompletions() { return fields.integer("spec", "completions"); }
    public Integer getBackoffLimit() { return fields.integer("spec", "backoffLimit"); }
    public Long getActiveDeadlineSeconds() { return fields.longNumber("spec", "activeDeadlineSeconds"); }
    public Boolean getSuspend() { return fields.bool("spec", "suspend"); }
    public Integer getActive() { return fields.integer("status", "active"); }
    public Integer getSucceeded() { return fields.integer("status", "succeeded"); }
    public Integer getFailed() { return fields.integer("status", "failed"); }
    public Integer getReady() { return fields.integer("status", "ready"); }
    public String getStartTime() { return fields.string("status", "startTime"); }
    public String getCompletionTime() { return fields.string("status", "completionTime"); }
}
