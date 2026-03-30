package com.hyqp.broker.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lightweight representation of a parsed JSON value.
 */
public final class JsonValue {

    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    public static final JsonValue NULL = new JsonValue(Type.NULL);

    private final Type type;
    private final Object value;

    // Object
    public JsonValue(Map<String, JsonValue> map) {
        this.type = Type.OBJECT;
        this.value = map;
    }

    // Array
    public JsonValue(List<JsonValue> list) {
        this.type = Type.ARRAY;
        this.value = list;
    }

    // String
    public JsonValue(String s) {
        this.type = Type.STRING;
        this.value = s;
    }

    // Number
    public JsonValue(double n) {
        this.type = Type.NUMBER;
        this.value = n;
    }

    // Boolean
    public JsonValue(boolean b) {
        this.type = Type.BOOLEAN;
        this.value = b;
    }

    // Null
    private JsonValue(Type type) {
        this.type = type;
        this.value = null;
    }

    public Type getType() {
        return type;
    }

    public boolean isObject() { return type == Type.OBJECT; }
    public boolean isArray()  { return type == Type.ARRAY; }
    public boolean isString() { return type == Type.STRING; }
    public boolean isNumber() { return type == Type.NUMBER; }
    public boolean isBoolean() { return type == Type.BOOLEAN; }
    public boolean isNull()   { return type == Type.NULL; }

    /**
     * Returns true if the number has no fractional part (e.g., 5.0 → true, 5.3 → false).
     */
    public boolean isInteger() {
        if (type != Type.NUMBER) return false;
        double d = (Double) value;
        return d == Math.floor(d) && !Double.isInfinite(d);
    }

    @SuppressWarnings("unchecked")
    public Map<String, JsonValue> asObject() {
        if (type != Type.OBJECT) throw new IllegalStateException("Not an object");
        return (Map<String, JsonValue>) value;
    }

    @SuppressWarnings("unchecked")
    public List<JsonValue> asArray() {
        if (type != Type.ARRAY) throw new IllegalStateException("Not an array");
        return (List<JsonValue>) value;
    }

    public String asString() {
        if (type != Type.STRING) throw new IllegalStateException("Not a string");
        return (String) value;
    }

    public double asNumber() {
        if (type != Type.NUMBER) throw new IllegalStateException("Not a number");
        return (Double) value;
    }

    public boolean asBoolean() {
        if (type != Type.BOOLEAN) throw new IllegalStateException("Not a boolean");
        return (Boolean) value;
    }

    /**
     * Get a field from an object, or null if missing.
     */
    public JsonValue get(String key) {
        if (type != Type.OBJECT) return null;
        return asObject().get(key);
    }

    /**
     * Check if an object has a given key.
     */
    public boolean has(String key) {
        return type == Type.OBJECT && asObject().containsKey(key);
    }

    public String getTypeName() {
        return switch (type) {
            case OBJECT  -> "object";
            case ARRAY   -> "array";
            case STRING  -> "string";
            case NUMBER  -> isInteger() ? "integer" : "number";
            case BOOLEAN -> "boolean";
            case NULL    -> "null";
        };
    }

    @Override
    public String toString() {
        return switch (type) {
            case OBJECT  -> asObject().toString();
            case ARRAY   -> asArray().toString();
            case STRING  -> "\"" + value + "\"";
            case NUMBER  -> {
                double d = (Double) value;
                yield d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> value.toString();
            case NULL    -> "null";
        };
    }
}
