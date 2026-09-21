package com.iskycc.k8s.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.List;
import java.util.Map;

/** 容器规格；环境变量、命令等字段可能敏感，toString 不输出内容。 */
public final class ContainerDetails {
    private final JsonFields fields;

    public ContainerDetails(JsonObject value) { fields = new JsonFields(value); }

    public String getName() { return fields.string("name"); }
    public String getImage() { return fields.string("image"); }
    public String getImagePullPolicy() { return fields.string("imagePullPolicy"); }
    public String getWorkingDir() { return fields.string("workingDir"); }
    public List<String> getCommand() { return fields.strings("command"); }
    public List<String> getArgs() { return fields.strings("args"); }
    public JsonArray getPorts() { return fields.array("ports"); }
    public JsonArray getEnv() { return fields.array("env"); }
    public JsonArray getEnvFrom() { return fields.array("envFrom"); }
    public JsonArray getVolumeMounts() { return fields.array("volumeMounts"); }
    public JsonObject getResources() { return fields.object("resources"); }
    public Map<String, String> getRequests() { return fields.stringMap("resources", "requests"); }
    public Map<String, String> getLimits() { return fields.stringMap("resources", "limits"); }
    public JsonObject getReadinessProbe() { return fields.object("readinessProbe"); }
    public JsonObject getLivenessProbe() { return fields.object("livenessProbe"); }
    public JsonObject getStartupProbe() { return fields.object("startupProbe"); }
    public JsonObject getSecurityContext() { return fields.object("securityContext"); }
    public JsonObject toJson() { return fields.object(); }

    @Override public String toString() { return "ContainerDetails"; }
}
