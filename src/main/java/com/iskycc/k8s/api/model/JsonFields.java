package com.iskycc.k8s.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 详细结果内部的只读 JSON 快照；所有可变返回值均与快照隔离。 */
final class JsonFields {
    private final JsonObject value;

    JsonFields(JsonObject value) { this.value = value.deepCopy(); }

    private JsonElement find(String... path) {
        JsonElement current = value;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) { return null; }
            current = current.getAsJsonObject().get(key);
        }
        return current;
    }

    String string(String... path) {
        JsonElement element = find(path);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    Integer integer(String... path) {
        JsonElement element = find(path);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) { return null; }
        try { return element.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { return null; }
    }

    Long longNumber(String... path) {
        JsonElement element = find(path);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) { return null; }
        try { return element.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { return null; }
    }

    Boolean bool(String... path) {
        JsonElement element = find(path);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : null;
    }

    JsonObject object(String... path) {
        JsonElement element = find(path);
        return element != null && element.isJsonObject() ? element.getAsJsonObject().deepCopy() : new JsonObject();
    }

    JsonArray array(String... path) {
        JsonElement element = find(path);
        return element != null && element.isJsonArray() ? element.getAsJsonArray().deepCopy() : new JsonArray();
    }

    Map<String, String> stringMap(String... path) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        JsonElement element = find(path);
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) { result.put(entry.getKey(), entry.getValue().getAsString()); }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    List<String> strings(String... path) {
        List<String> result = new ArrayList<String>();
        JsonElement element = find(path);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) { result.add(item.getAsString()); }
            }
        }
        return Collections.unmodifiableList(result);
    }

    List<String> propertyList(String property, String... path) {
        List<String> result = new ArrayList<String>();
        JsonElement element = find(path);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) { continue; }
                JsonElement text = item.getAsJsonObject().get(property);
                if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()) {
                    result.add(text.getAsString());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    <T> List<T> objects(Function<JsonObject, T> mapper, String... path) {
        List<T> result = new ArrayList<T>();
        JsonElement element = find(path);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) { result.add(mapper.apply(item.getAsJsonObject())); }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
