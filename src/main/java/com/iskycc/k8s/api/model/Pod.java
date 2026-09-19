package com.iskycc.k8s.api.model;

/**
 * Pod 常用字段子集。
 */
public class Pod {
    private ObjectMeta metadata;
    private Spec spec;
    private Status status;

    public ObjectMeta getMetadata() { return metadata; }
    public Spec getSpec() { return spec; }
    public Status getStatus() { return status; }

    public String getName() { return metadata != null ? metadata.getName() : null; }
    public String getNamespace() { return metadata != null ? metadata.getNamespace() : null; }
    public String getPhase() { return status != null ? status.getPhase() : null; }
    public String getNodeName() { return spec != null ? spec.getNodeName() : null; }

    public static class Spec {
        private String nodeName;
        private String serviceAccountName;

        public String getNodeName() { return nodeName; }
        public String getServiceAccountName() { return serviceAccountName; }
    }

    public static class Status {
        private String phase;
        private String podIP;
        private String hostIP;
        private String startTime;

        public String getPhase() { return phase; }
        public String getPodIP() { return podIP; }
        public String getHostIP() { return hostIP; }
        public String getStartTime() { return startTime; }
    }

    @Override
    public String toString() {
        return "Pod{" + getNamespace() + "/" + getName() + " phase=" + getPhase()
                + " node=" + getNodeName() + "}";
    }
}
