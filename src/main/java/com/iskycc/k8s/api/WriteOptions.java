package com.iskycc.k8s.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建、替换、Patch、Apply 的不可变请求参数。 */
public final class WriteOptions {
    private final Map<String, String> query;

    private WriteOptions(Builder b) {
        query = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.query));
    }

    public static Builder builder() { return new Builder(); }
    public Map<String, String> toQueryParameters() { return query; }

    public static final class Builder {
        private final Map<String, String> query = new LinkedHashMap<String, String>();

        public Builder dryRun(boolean value) {
            if (value) { query.put("dryRun", "All"); } else { query.remove("dryRun"); }
            return this;
        }
        public Builder fieldManager(String value) {
            if (value == null || value.trim().isEmpty() || value.length() > 128) {
                throw new IllegalArgumentException("fieldManager must contain 1..128 characters");
            }
            query.put("fieldManager", value);
            return this;
        }
        public Builder fieldValidation(String value) {
            if (!"Ignore".equals(value) && !"Warn".equals(value) && !"Strict".equals(value)) {
                throw new IllegalArgumentException("fieldValidation must be Ignore, Warn or Strict");
            }
            query.put("fieldValidation", value);
            return this;
        }
        public WriteOptions build() { return new WriteOptions(this); }
    }
}
