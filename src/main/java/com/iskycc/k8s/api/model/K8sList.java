package com.iskycc.k8s.api.model;

import java.util.Collections;
import java.util.List;

/**
 * k8s 列表响应的通用包装（PodList/NodeList/...）。
 */
public class K8sList<T> {
    private String kind;
    private String apiVersion;
    private ListMeta metadata;
    private List<T> items;

    public String getKind() { return kind; }
    public String getApiVersion() { return apiVersion; }
    public ListMeta getMetadata() { return metadata; }

    public List<T> getItems() {
        return items == null ? Collections.<T>emptyList() : items;
    }

    public static class ListMeta {
        private String resourceVersion;

        public String getResourceVersion() { return resourceVersion; }
    }
}
