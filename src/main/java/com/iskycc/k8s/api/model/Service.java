package com.iskycc.k8s.api.model;

import java.util.Collections;
import java.util.List;

/**
 * Service 常用字段子集。
 */
public class Service {
    private ObjectMeta metadata;
    private Spec spec;

    public ObjectMeta getMetadata() { return metadata; }
    public Spec getSpec() { return spec; }

    public String getName() { return metadata != null ? metadata.getName() : null; }
    public String getNamespace() { return metadata != null ? metadata.getNamespace() : null; }
    public String getType() { return spec != null ? spec.getType() : null; }
    public String getClusterIP() { return spec != null ? spec.getClusterIP() : null; }

    public static class Spec {
        private String type;
        private String clusterIP;
        private List<Port> ports;

        public String getType() { return type; }
        public String getClusterIP() { return clusterIP; }
        public List<Port> getPorts() {
            return ports == null ? Collections.<Port>emptyList() : ports;
        }
    }

    public static class Port {
        private String name;
        private int port;
        // targetPort 为 IntOrString，统一按字符串读取
        private String targetPort;
        private Integer nodePort;
        private String protocol;

        public String getName() { return name; }
        public int getPort() { return port; }
        public String getTargetPort() { return targetPort; }
        public Integer getNodePort() { return nodePort; }
        public String getProtocol() { return protocol; }
    }

    @Override
    public String toString() {
        return "Service{" + getNamespace() + "/" + getName() + " type=" + getType()
                + " clusterIP=" + getClusterIP() + "}";
    }
}
