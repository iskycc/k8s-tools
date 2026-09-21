package com.iskycc.k8s.api;

import com.google.gson.JsonObject;
import com.iskycc.k8s.api.model.ResourceSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 公共搜索入口共享的跨 namespace 分页与名称匹配逻辑，不做唯一选择或状态过滤。 */
final class ResourceSearch {
    private ResourceSearch() { }

    static <T> List<T> search(K8sApiClient client, ResourceDefinition definition, String keyword,
                              ListOptions options, Function<JsonObject, T> mapper) {
        if (definition == null || !definition.isNamespaced()) {
            throw new IllegalArgumentException("Search requires a namespaced resource definition");
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("Search keyword must not be blank; use listAll to list every resource");
        }
        ListOptions query = options == null ? ListOptions.builder().limit(100).build() : options;
        if (query.toQueryParameters().containsKey("continue")) {
            throw new IllegalArgumentException("Search starts at the first page; continueToken is not supported");
        }
        List<T> matches = new ArrayList<T>();
        for (JsonObject item : client.resource(definition).inAllNamespaces().listAll(query)) {
            ResourceSummary identity = ResourceSummary.fromJson(item);
            if (identity.getName().contains(keyword)) { matches.add(mapper.apply(item)); }
        }
        return matches;
    }
}
