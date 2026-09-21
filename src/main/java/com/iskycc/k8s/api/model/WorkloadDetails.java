package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** 具有 spec.template 的工作负载公共信息；容器来自模板，不代表实际 Pod 状态。 */
public abstract class WorkloadDetails extends ResourceDetails {
    protected WorkloadDetails(JsonObject resource, String apiVersion, String kind) {
        super(resource, apiVersion, kind);
    }

    public Long getObservedGeneration() { return fields.longNumber("status", "observedGeneration"); }
    public JsonObject getSelector() { return fields.object("spec", "selector"); }
    public Map<String, String> getMatchLabels() { return fields.stringMap("spec", "selector", "matchLabels"); }
    public JsonObject getTemplate() { return fields.object("spec", "template"); }
    public List<ContainerDetails> getContainers() { return fields.objects(ContainerDetails::new, "spec", "template", "spec", "containers"); }
    public List<ContainerDetails> getInitContainers() { return fields.objects(ContainerDetails::new, "spec", "template", "spec", "initContainers"); }
    public List<String> getContainerNames() { return fields.propertyList("name", "spec", "template", "spec", "containers"); }
    public List<String> getImages() { return fields.propertyList("image", "spec", "template", "spec", "containers"); }
    public List<ResourceCondition> getConditions() { return fields.objects(ResourceCondition::new, "status", "conditions"); }
}
