package com.iskycc.k8s.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 删除参数；支持级联策略、宽限期、dry-run 及防止误删新对象的前置条件。 */
public final class DeleteOptions {
    public enum PropagationPolicy { Background, Foreground, Orphan }

    private final JsonObject body;

    private DeleteOptions(Builder b) { body = b.body.deepCopy(); }
    public static Builder builder() { return new Builder(); }
    public JsonObject toJson() { return body.deepCopy(); }

    public static final class Builder {
        private final JsonObject body = new JsonObject();

        private Builder() {
            body.addProperty("apiVersion", "v1");
            body.addProperty("kind", "DeleteOptions");
        }
        public Builder gracePeriodSeconds(long value) {
            if (value < 0) { throw new IllegalArgumentException("gracePeriodSeconds must be >= 0"); }
            body.addProperty("gracePeriodSeconds", value);
            return this;
        }
        public Builder propagationPolicy(PropagationPolicy value) {
            if (value == null) { throw new IllegalArgumentException("propagationPolicy is required"); }
            body.addProperty("propagationPolicy", value.name());
            return this;
        }
        public Builder uid(String value) { return precondition("uid", value); }
        public Builder resourceVersion(String value) { return precondition("resourceVersion", value); }
        private Builder precondition(String key, String value) {
            if (value == null || value.isEmpty()) { throw new IllegalArgumentException(key + " is required"); }
            if (!body.has("preconditions")) { body.add("preconditions", new JsonObject()); }
            body.getAsJsonObject("preconditions").addProperty(key, value);
            return this;
        }
        public Builder dryRun(boolean value) {
            if (value) {
                JsonArray values = new JsonArray();
                values.add("All");
                body.add("dryRun", values);
            } else { body.remove("dryRun"); }
            return this;
        }
        public DeleteOptions build() { return new DeleteOptions(this); }
    }
}
