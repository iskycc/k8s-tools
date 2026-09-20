package com.iskycc.k8s.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** API Discovery 中的一项资源，name 可能包含 pods/status 等子资源。 */
public final class ApiResource {
    private String name;
    private String singularName;
    private String kind;
    private boolean namespaced;
    private List<String> verbs;
    private List<String> shortNames;

    public String getName() { return name; }
    public String getSingularName() { return singularName; }
    public String getKind() { return kind; }
    public boolean isNamespaced() { return namespaced; }
    public boolean isSubresource() { return name != null && name.contains("/"); }
    public List<String> getVerbs() {
        return verbs == null ? Collections.<String>emptyList() : Collections.unmodifiableList(verbs);
    }
    public List<String> getShortNames() {
        return shortNames == null ? Collections.<String>emptyList() : Collections.unmodifiableList(shortNames);
    }

    /** 子资源通过 K8sResourceClient.subresource 访问，不能作为独立集合。 */
    public ResourceDefinition toDefinition(String apiVersion) {
        if (isSubresource()) { throw new IllegalArgumentException("Use subresource() for " + name); }
        return ResourceDefinition.discovered(apiVersion, name, kind, namespaced,
                new LinkedHashSet<String>(getVerbs()));
    }
}
