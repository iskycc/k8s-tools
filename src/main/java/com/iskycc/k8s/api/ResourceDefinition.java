package com.iskycc.k8s.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Kubernetes 资源的版本、复数名称、Kind 和作用域；不推测 Kind 的复数形式。 */
public final class ResourceDefinition {
    private final String apiVersion;
    private final String plural;
    private final String kind;
    private final boolean namespaced;
    private final Set<String> verbs;

    private ResourceDefinition(String apiVersion, String plural, String kind, boolean namespaced,
                               Set<String> verbs) {
        if (apiVersion == null || !apiVersion.matches("(?:[a-z0-9][a-z0-9.-]*/)?[a-z](?:[-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("apiVersion must be v1 or group/version");
        }
        if (plural == null || !plural.matches("[a-z](?:[-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("resource must be a plural API resource name");
        }
        if (kind == null || !kind.matches("[A-Za-z](?:[-A-Za-z0-9]*[A-Za-z0-9])?")) {
            throw new IllegalArgumentException("kind is required");
        }
        this.apiVersion = apiVersion;
        this.plural = plural;
        this.kind = kind;
        this.namespaced = namespaced;
        this.verbs = verbs == null ? null : Collections.unmodifiableSet(new LinkedHashSet<String>(verbs));
    }

    public static ResourceDefinition namespaced(String apiVersion, String plural, String kind) {
        return new ResourceDefinition(apiVersion, plural, kind, true, null);
    }

    public static ResourceDefinition cluster(String apiVersion, String plural, String kind) {
        return new ResourceDefinition(apiVersion, plural, kind, false, null);
    }

    static ResourceDefinition discovered(String apiVersion, String plural, String kind,
                                         boolean namespaced, Set<String> verbs) {
        return new ResourceDefinition(apiVersion, plural, kind, namespaced, verbs);
    }

    public String getApiVersion() { return apiVersion; }
    public String getPlural() { return plural; }
    public String getKind() { return kind; }
    public boolean isNamespaced() { return namespaced; }

    /** Discovery 返回的服务端能力；手动定义时为空集合，不代表服务端不支持。 */
    public Set<String> getVerbs() { return verbs == null ? Collections.<String>emptySet() : verbs; }
    public boolean hasDiscoveredVerbs() { return verbs != null; }

    void requireVerb(String verb) {
        if (verbs != null && !verbs.contains(verb)) {
            throw new UnsupportedOperationException(apiVersion + "/" + plural + " does not support " + verb);
        }
    }

    String basePath() {
        return (apiVersion.indexOf('/') < 0 ? "/api/" : "/apis/") + apiVersion;
    }
}
