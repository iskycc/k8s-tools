package com.iskycc.k8s.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pod 简易搜索结果：namespace、Pod 名称和 spec.containers 中的普通容器名称。 */
public final class PodSummary extends ResourceSummary {
    private final List<String> containerNames;

    public PodSummary(String namespace, String name, List<String> containerNames) {
        super(namespace, name);
        this.containerNames = Collections.unmodifiableList(new ArrayList<String>(containerNames));
    }

    public List<String> getContainerNames() { return containerNames; }
    /** 与 getName 相同，便于直接传给 exec。 */
    public String getPodName() { return getName(); }

    public static PodSummary fromJson(JsonObject resource) {
        String namespace = identity(resource, "namespace");
        String name = identity(resource, "name");
        List<String> names = new ArrayList<String>();
        JsonElement spec = resource.get("spec");
        JsonElement containers = spec != null && spec.isJsonObject() ? spec.getAsJsonObject().get("containers") : null;
        if (containers != null && containers.isJsonArray()) {
            JsonArray items = containers.getAsJsonArray();
            for (JsonElement item : items) {
                if (item.isJsonObject() && item.getAsJsonObject().has("name")
                        && item.getAsJsonObject().get("name").isJsonPrimitive()) {
                    names.add(item.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        return new PodSummary(namespace, name, names);
    }
}
