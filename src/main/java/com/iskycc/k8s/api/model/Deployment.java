package com.iskycc.k8s.api.model;

/**
 * apps/v1 Deployment 常用字段子集。
 */
public class Deployment {
    private ObjectMeta metadata;
    private Spec spec;
    private Status status;

    public ObjectMeta getMetadata() { return metadata; }
    public Spec getSpec() { return spec; }
    public Status getStatus() { return status; }

    public String getName() { return metadata != null ? metadata.getName() : null; }
    public String getNamespace() { return metadata != null ? metadata.getNamespace() : null; }

    public Integer getReplicas() { return spec != null ? spec.getReplicas() : null; }

    public int getReadyReplicas() {
        return status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
    }

    public static class Spec {
        private Integer replicas;

        public Integer getReplicas() { return replicas; }
    }

    public static class Status {
        private Integer replicas;
        private Integer readyReplicas;
        private Integer availableReplicas;
        private Integer updatedReplicas;

        public Integer getReplicas() { return replicas; }
        public Integer getReadyReplicas() { return readyReplicas; }
        public Integer getAvailableReplicas() { return availableReplicas; }
        public Integer getUpdatedReplicas() { return updatedReplicas; }
    }

    @Override
    public String toString() {
        return "Deployment{" + getNamespace() + "/" + getName()
                + " replicas=" + getReplicas() + " ready=" + getReadyReplicas() + "}";
    }
}
