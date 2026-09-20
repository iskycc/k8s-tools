package com.iskycc.k8s.api;

/** Patch 的服务器端解释方式。CRD 通常不支持 Strategic Merge Patch。 */
public enum PatchType {
    JSON_PATCH("application/json-patch+json"),
    MERGE_PATCH("application/merge-patch+json"),
    STRATEGIC_MERGE_PATCH("application/strategic-merge-patch+json");

    private final String contentType;

    PatchType(String contentType) { this.contentType = contentType; }
    public String getContentType() { return contentType; }
}
