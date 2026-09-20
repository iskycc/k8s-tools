package com.iskycc.k8s.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不可变的列表参数，保留选择器原文，由 HTTP 层统一编码。 */
public final class ListOptions {
    private final Map<String, String> query;

    private ListOptions(Builder b) {
        query = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.query));
    }

    public static Builder builder() { return new Builder(); }
    public Map<String, String> toQueryParameters() { return query; }

    public ListOptions withContinueToken(String token) {
        Builder builder = new Builder();
        builder.query.putAll(query);
        return builder.continueToken(token).build();
    }

    public static final class Builder {
        private final Map<String, String> query = new LinkedHashMap<String, String>();

        public Builder labelSelector(String value) { return put("labelSelector", value); }
        public Builder fieldSelector(String value) { return put("fieldSelector", value); }
        public Builder resourceVersion(String value) { return put("resourceVersion", value); }
        public Builder continueToken(String value) { return put("continue", value); }
        public Builder limit(long value) {
            if (value < 0) { throw new IllegalArgumentException("limit must be >= 0"); }
            return put("limit", Long.toString(value));
        }
        public Builder timeoutSeconds(long value) {
            if (value <= 0) { throw new IllegalArgumentException("timeoutSeconds must be > 0"); }
            return put("timeoutSeconds", Long.toString(value));
        }
        public Builder resourceVersionMatch(String value) {
            if (value != null && !"Exact".equals(value) && !"NotOlderThan".equals(value)) {
                throw new IllegalArgumentException("resourceVersionMatch must be Exact or NotOlderThan");
            }
            return put("resourceVersionMatch", value);
        }

        private Builder put(String key, String value) {
            if (value == null || value.isEmpty()) { query.remove(key); }
            else { query.put(key, value); }
            return this;
        }

        public ListOptions build() {
            if (query.containsKey("resourceVersionMatch") && !query.containsKey("resourceVersion")) {
                throw new IllegalArgumentException("resourceVersionMatch requires resourceVersion");
            }
            return new ListOptions(this);
        }
    }
}
