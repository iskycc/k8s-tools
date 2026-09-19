package com.iskycc.k8s.api.model;

/**
 * Namespace 常用字段子集。
 */
public class Namespace {
    private ObjectMeta metadata;
    private Status status;

    public ObjectMeta getMetadata() { return metadata; }
    public Status getStatus() { return status; }

    public String getName() { return metadata != null ? metadata.getName() : null; }
    public String getPhase() { return status != null ? status.getPhase() : null; }

    public static class Status {
        private String phase;

        public String getPhase() { return phase; }
    }

    @Override
    public String toString() {
        return "Namespace{" + getName() + " phase=" + getPhase() + "}";
    }
}
