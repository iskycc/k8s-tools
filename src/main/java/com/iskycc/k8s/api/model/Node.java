package com.iskycc.k8s.api.model;

import java.util.Collections;
import java.util.List;

/**
 * Node 常用字段子集，附带 Ready 状态与 InternalIP 便捷方法。
 */
public class Node {
    private ObjectMeta metadata;
    private Status status;

    public ObjectMeta getMetadata() { return metadata; }
    public Status getStatus() { return status; }

    public String getName() { return metadata != null ? metadata.getName() : null; }

    /** 依据 conditions 中 type=Ready 的 status 是否为 "True" 判断节点就绪。 */
    public boolean isReady() {
        if (status == null || status.getConditions() == null) {
            return false;
        }
        for (Condition c : status.getConditions()) {
            if ("Ready".equals(c.getType())) {
                return "True".equals(c.getStatus());
            }
        }
        return false;
    }

    /** InternalIP 地址，找不到返回 null。 */
    public String getInternalIp() {
        if (status == null || status.getAddresses() == null) {
            return null;
        }
        for (Address a : status.getAddresses()) {
            if ("InternalIP".equals(a.getType())) {
                return a.getAddress();
            }
        }
        return null;
    }

    public static class Status {
        private List<Condition> conditions;
        private List<Address> addresses;

        public List<Condition> getConditions() {
            return conditions == null ? Collections.<Condition>emptyList() : conditions;
        }

        public List<Address> getAddresses() {
            return addresses == null ? Collections.<Address>emptyList() : addresses;
        }
    }

    public static class Condition {
        private String type;
        private String status;
        private String reason;
        private String lastTransitionTime;

        public String getType() { return type; }
        public String getStatus() { return status; }
        public String getReason() { return reason; }
        public String getLastTransitionTime() { return lastTransitionTime; }
    }

    public static class Address {
        private String type;
        private String address;

        public String getType() { return type; }
        public String getAddress() { return address; }
    }

    @Override
    public String toString() {
        return "Node{" + getName() + " ready=" + isReady() + " ip=" + getInternalIp() + "}";
    }
}
