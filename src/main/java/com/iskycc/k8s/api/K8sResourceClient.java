package com.iskycc.k8s.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.iskycc.k8s.K8sToolsException;
import com.iskycc.k8s.api.model.K8sList;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 绑定资源定义和作用域的不可变 REST 入口，JSON 对象保留所有未知字段。
 * 集合查询可跨命名空间，命名空间资源的单对象操作必须先 inNamespace。
 * 服务端的 RBAC、准入、资源版本与不可变字段校验仍以 API Server 为准。
 */
public final class K8sResourceClient {
    private static final Gson GSON = new Gson();
    private final K8sApiClient client;
    private final ResourceDefinition definition;
    private final String namespace;

    K8sResourceClient(K8sApiClient client, ResourceDefinition definition, String namespace) {
        this.client = client;
        this.definition = definition;
        this.namespace = namespace;
    }

    public ResourceDefinition getDefinition() { return definition; }
    public String getNamespace() { return namespace; }

    /** namespace 必须是具体名称；字符串 all 在这里表示真正名为 all 的命名空间。 */
    public K8sResourceClient inNamespace(String namespace) {
        if (!definition.isNamespaced()) { throw new IllegalArgumentException("Resource is cluster-scoped"); }
        if (namespace == null || !namespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
                || namespace.length() > 63) {
            throw new IllegalArgumentException("namespace must be a concrete DNS label");
        }
        return new K8sResourceClient(client, definition, namespace);
    }

    public K8sResourceClient inAllNamespaces() {
        if (!definition.isNamespaced()) { throw new IllegalArgumentException("Resource is cluster-scoped"); }
        return new K8sResourceClient(client, definition, null);
    }

    public JsonObject get(String name) {
        definition.requireVerb("get");
        return K8sApiClient.parseObject(client.getRaw(objectPath(name)));
    }

    /** 仅将 404 转为 false，认证、授权、网络等错误照常抛出。 */
    public boolean exists(String name) {
        try { get(name); return true; }
        catch (K8sApiException e) {
            if (e.getStatusCode() == 404) { return false; }
            throw e;
        }
    }

    public K8sList<JsonObject> list() { return list(ListOptions.builder().build()); }

    /** 返回单页数据及 continue/resourceVersion，调用方可自行控制分页。 */
    public K8sList<JsonObject> list(ListOptions options) {
        definition.requireVerb("list");
        JsonObject json = K8sApiClient.parseObject(client.request("GET", collectionPath(),
                options == null ? null : options.toQueryParameters(), null, null).getBody());
        if (!json.has("items") || !json.get("items").isJsonArray()) {
            throw new K8sToolsException("List response has no items array");
        }
        for (JsonElement item : json.getAsJsonArray("items")) {
            if (!item.isJsonObject()) { throw new K8sToolsException("List item must be a JSON object"); }
        }
        try {
            return GSON.fromJson(json, new TypeToken<K8sList<JsonObject>>() { }.getType());
        } catch (JsonSyntaxException e) {
            throw new K8sToolsException("Invalid resource list metadata", e);
        }
    }

    /** 收集所有分页到内存；410/过期 continue 不静默重启，以免混合不同快照。 */
    public List<JsonObject> listAll(ListOptions options) {
        ListOptions next = options == null ? ListOptions.builder().build() : options;
        List<JsonObject> items = new ArrayList<JsonObject>();
        Set<String> seen = new HashSet<String>();
        String initial = next.toQueryParameters().get("continue");
        if (initial != null) { seen.add(initial); }
        String resourceVersion = null;
        while (true) {
            K8sList<JsonObject> page = list(next);
            String currentVersion = page.getMetadata() == null ? null : page.getMetadata().getResourceVersion();
            if (resourceVersion != null && currentVersion != null && !resourceVersion.equals(currentVersion)) {
                throw new K8sToolsException("Resource version changed between list pages");
            }
            if (currentVersion != null) { resourceVersion = currentVersion; }
            items.addAll(page.getItems());
            String token = page.getMetadata() == null ? null : page.getMetadata().getContinueToken();
            if (token == null || token.isEmpty()) { return items; }
            if (!seen.add(token)) { throw new K8sToolsException("Repeated pagination continue token"); }
            next = next.withContinueToken(token);
        }
    }

    public List<JsonObject> listAll() { return listAll(ListOptions.builder().build()); }

    public JsonObject create(JsonObject resource) { return create(resource, null); }

    /** 支持 name、generateName 和无名称的审查资源；必需字段由服务端校验，不修改传入的 JSON。 */
    public JsonObject create(JsonObject resource, WriteOptions options) {
        definition.requireVerb("create");
        requireConcreteScope();
        JsonObject document = prepare(resource, null);
        JsonObject metadata = document.getAsJsonObject("metadata");
        String name = string(metadata, "name");
        if (!name.isEmpty()) { pathSegment(name); }
        return write("POST", collectionPath(), document, "application/json", query(options));
    }

    public JsonObject replace(String name, JsonObject resource) { return replace(name, resource, null); }

    /** 完整 PUT 替换，要求保留 GET 返回的 resourceVersion；409 由调用方重新读取和合并。 */
    public JsonObject replace(String name, JsonObject resource, WriteOptions options) {
        definition.requireVerb("update");
        JsonObject document = prepare(resource, name);
        if (string(document.getAsJsonObject("metadata"), "resourceVersion").isEmpty()) {
            throw new IllegalArgumentException("replace requires metadata.resourceVersion from a fresh GET");
        }
        return write("PUT", objectPath(name), document, "application/json", query(options));
    }

    public JsonObject patch(String name, PatchType type, JsonElement patch) {
        return patch(name, type, patch, null);
    }

    public JsonObject patch(String name, PatchType type, JsonElement patch, WriteOptions options) {
        definition.requireVerb("patch");
        validatePatch(type, patch);
        return write("PATCH", objectPath(name), patch, type.getContentType(), query(options));
    }

    public JsonObject apply(JsonObject resource, String fieldManager, boolean force) {
        return apply(resource, WriteOptions.builder().fieldManager(fieldManager).build(), force);
    }

    /** Server-Side Apply；JSON 是 YAML 的子集。fieldManager 必填，force 必须由调用方明确指定。 */
    public JsonObject apply(JsonObject resource, WriteOptions options, boolean force) {
        definition.requireVerb("patch");
        Map<String, String> parameters = query(options);
        if (!parameters.containsKey("fieldManager")) { throw new IllegalArgumentException("apply requires fieldManager"); }
        JsonObject document = prepare(resource, null);
        String name = string(document.getAsJsonObject("metadata"), "name");
        parameters.put("force", Boolean.toString(force));
        return write("PATCH", objectPath(name), document, "application/apply-patch+yaml", parameters);
    }

    public JsonObject delete(String name) { return delete(name, null); }

    /** 返回服务端 Status 或资源对象，204 时返回 null；成功不等于 finalizer 已完成。 */
    public JsonObject delete(String name, DeleteOptions options) {
        definition.requireVerb("delete");
        return write("DELETE", objectPath(name), deleteBody(options), "application/json", null);
    }

    public boolean deleteIfExists(String name, DeleteOptions options) {
        try { delete(name, options); return true; }
        catch (K8sApiException e) {
            if (e.getStatusCode() == 404) { return false; }
            throw e;
        }
    }

    /** 删除当前作用域中匹配选择器的集合；未指定选择器即全部，服务端需支持 deletecollection。 */
    public JsonObject deleteCollection(ListOptions selection, DeleteOptions options) {
        definition.requireVerb("deletecollection");
        requireConcreteScope();
        return write("DELETE", collectionPath(), deleteBody(options), "application/json",
                selection == null ? null : selection.toQueryParameters());
    }

    /** 访问 status、scale、eviction、token 等 REST 子资源；实际动作由服务端决定。 */
    public K8sSubresourceClient subresource(String name, String subresource) {
        return new K8sSubresourceClient(client, objectPath(name) + "/" + pathSegment(subresource));
    }

    /** 对支持 scale 子资源的工作负载调整副本数。 */
    public JsonObject scale(String name, int replicas) {
        if (replicas < 0) { throw new IllegalArgumentException("replicas must be >= 0"); }
        JsonObject patch = new JsonObject();
        JsonObject spec = new JsonObject();
        spec.addProperty("replicas", replicas);
        patch.add("spec", spec);
        return subresource(name, "scale").patch(PatchType.MERGE_PATCH, patch, null);
    }

    private JsonObject prepare(JsonObject resource, String name) {
        requireConcreteScope();
        if (resource == null) { throw new IllegalArgumentException("resource is required"); }
        JsonObject copy = resource.deepCopy();
        setMatching(copy, "apiVersion", definition.getApiVersion());
        setMatching(copy, "kind", definition.getKind());
        if (!copy.has("metadata")) { copy.add("metadata", new JsonObject()); }
        if (!copy.get("metadata").isJsonObject()) { throw new IllegalArgumentException("metadata must be an object"); }
        JsonObject metadata = copy.getAsJsonObject("metadata");
        if (name != null) { pathSegment(name); setMatching(metadata, "name", name); }
        if (definition.isNamespaced()) { setMatching(metadata, "namespace", namespace); }
        else if (!string(metadata, "namespace").isEmpty()) {
            throw new IllegalArgumentException("cluster-scoped resource must not contain metadata.namespace");
        }
        return copy;
    }

    private static void setMatching(JsonObject object, String key, String expected) {
        String actual = string(object, key);
        if (!actual.isEmpty() && !actual.equals(expected)) {
            throw new IllegalArgumentException(key + " does not match target resource");
        }
        object.addProperty(key, expected);
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) { return ""; }
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return object.get(key).getAsString();
    }

    private String collectionPath() {
        return definition.basePath() + (namespace == null ? "" : "/namespaces/" + pathSegment(namespace))
                + "/" + definition.getPlural();
    }

    private String objectPath(String name) {
        requireConcreteScope();
        return collectionPath() + "/" + pathSegment(name);
    }

    private void requireConcreteScope() {
        if (definition.isNamespaced() && namespace == null) {
            throw new IllegalArgumentException("Call inNamespace() before single-resource or write operations");
        }
    }

    static String pathSegment(String value) {
        if (value == null || value.trim().isEmpty() || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\") || value.contains("%")
                || value.contains("?") || value.contains("#") || value.matches(".*[\\s\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("Invalid API path segment");
        }
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    static void validatePatch(PatchType type, JsonElement patch) {
        if (type == null || patch == null || (type == PatchType.JSON_PATCH ? !patch.isJsonArray() : !patch.isJsonObject())) {
            throw new IllegalArgumentException("JSON Patch requires an array; merge patches require an object");
        }
    }

    static Map<String, String> query(WriteOptions options) {
        return new LinkedHashMap<String, String>(options == null
                ? Collections.<String, String>emptyMap() : options.toQueryParameters());
    }

    private static JsonObject deleteBody(DeleteOptions options) {
        return (options == null ? DeleteOptions.builder().build() : options).toJson();
    }

    private JsonObject write(String method, String path, JsonElement body, String contentType,
                             Map<String, String> query) {
        String response = client.request(method, path, query, body.toString(), contentType).getBody();
        return response.trim().isEmpty() ? null : K8sApiClient.parseObject(response);
    }
}
