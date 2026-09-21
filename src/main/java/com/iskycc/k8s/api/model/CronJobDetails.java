package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;

/** CronJob 的调度配置、最近执行时间及 Job 模板。
 * 标量缺失时为 null，集合缺失时为空；所有返回值与原始 JSON 隔离。
 */
public final class CronJobDetails extends ResourceDetails {
    public CronJobDetails(JsonObject resource) { super(resource, "batch/v1", "CronJob"); }

    public String getSchedule() { return fields.string("spec", "schedule"); }
    public String getTimeZone() { return fields.string("spec", "timeZone"); }
    public String getConcurrencyPolicy() { return fields.string("spec", "concurrencyPolicy"); }
    public Boolean getSuspend() { return fields.bool("spec", "suspend"); }
    public Long getStartingDeadlineSeconds() { return fields.longNumber("spec", "startingDeadlineSeconds"); }
    public Integer getSuccessfulJobsHistoryLimit() { return fields.integer("spec", "successfulJobsHistoryLimit"); }
    public Integer getFailedJobsHistoryLimit() { return fields.integer("spec", "failedJobsHistoryLimit"); }
    public String getLastScheduleTime() { return fields.string("status", "lastScheduleTime"); }
    public String getLastSuccessfulTime() { return fields.string("status", "lastSuccessfulTime"); }
    public JsonArray getActiveJobs() { return fields.array("status", "active"); }
    public JsonObject getJobTemplate() { return fields.object("spec", "jobTemplate"); }
    /** Job 模板的普通容器定义，不是实际运行容器状态。 */
    public List<ContainerDetails> getContainers() { return fields.objects(ContainerDetails::new, "spec", "jobTemplate", "spec", "template", "spec", "containers"); }
    public List<String> getContainerNames() { return fields.propertyList("name", "spec", "jobTemplate", "spec", "template", "spec", "containers"); }
}
