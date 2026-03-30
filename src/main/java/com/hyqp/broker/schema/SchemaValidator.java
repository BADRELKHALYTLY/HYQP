package com.hyqp.broker.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a JSON payload against a JSON Schema definition.
 *
 * Supported constraints:
 *   - "type"       : "object", "array", "string", "number", "integer", "boolean"
 *   - "required"   : ["field1", "field2"]  (for objects)
 *   - "properties" : { "field": { schema } }  (for objects, recursive)
 *   - "minimum"    : number  (for number/integer)
 *   - "maximum"    : number  (for number/integer)
 *   - "minLength"  : integer (for strings)
 *   - "maxLength"  : integer (for strings)
 *   - "enum"       : [value1, value2, ...]  (allowed values)
 *   - "items"      : { schema }  (for arrays, validates each element)
 *
 * Example schema:
 * {
 *   "type": "object",
 *   "required": ["value"],
 *   "properties": {
 *     "value": { "type": "number", "minimum": -50, "maximum": 100 },
 *     "unit":  { "type": "string", "enum": ["celsius", "fahrenheit"] }
 *   }
 * }
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /**
     * Validates a payload against a schema.
     * @return list of validation errors (empty if valid)
     */
    public static List<String> validate(JsonValue payload, JsonValue schema) {
        List<String> errors = new ArrayList<>();
        validateNode(payload, schema, "$", errors);
        return errors;
    }

    private static void validateNode(JsonValue value, JsonValue schema, String path, List<String> errors) {
        if (!schema.isObject()) {
            errors.add(path + ": schema must be an object");
            return;
        }

        // --- type ---
        JsonValue typeNode = schema.get("type");
        if (typeNode != null && typeNode.isString()) {
            String expectedType = typeNode.asString();
            if (!matchesType(value, expectedType)) {
                errors.add(path + ": expected type '" + expectedType + "' but got '" + value.getTypeName() + "'");
                return; // no point checking further constraints if type is wrong
            }
        }

        // --- enum ---
        JsonValue enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            if (!matchesEnum(value, enumNode.asArray())) {
                errors.add(path + ": value " + value + " is not in enum " + enumNode);
            }
        }

        // --- string constraints ---
        if (value.isString()) {
            String s = value.asString();
            JsonValue minLen = schema.get("minLength");
            if (minLen != null && minLen.isNumber()) {
                if (s.length() < (int) minLen.asNumber()) {
                    errors.add(path + ": string length " + s.length() + " is less than minLength " + (int) minLen.asNumber());
                }
            }
            JsonValue maxLen = schema.get("maxLength");
            if (maxLen != null && maxLen.isNumber()) {
                if (s.length() > (int) maxLen.asNumber()) {
                    errors.add(path + ": string length " + s.length() + " exceeds maxLength " + (int) maxLen.asNumber());
                }
            }
        }

        // --- number constraints ---
        if (value.isNumber()) {
            double n = value.asNumber();
            JsonValue min = schema.get("minimum");
            if (min != null && min.isNumber()) {
                if (n < min.asNumber()) {
                    errors.add(path + ": value " + n + " is less than minimum " + min.asNumber());
                }
            }
            JsonValue max = schema.get("maximum");
            if (max != null && max.isNumber()) {
                if (n > max.asNumber()) {
                    errors.add(path + ": value " + n + " exceeds maximum " + max.asNumber());
                }
            }
        }

        // --- object constraints ---
        if (value.isObject()) {
            Map<String, JsonValue> obj = value.asObject();

            // required
            JsonValue requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                for (JsonValue req : requiredNode.asArray()) {
                    if (req.isString() && !obj.containsKey(req.asString())) {
                        errors.add(path + ": missing required field '" + req.asString() + "'");
                    }
                }
            }

            // properties (recursive validation)
            JsonValue propsNode = schema.get("properties");
            if (propsNode != null && propsNode.isObject()) {
                Map<String, JsonValue> propSchemas = propsNode.asObject();
                for (var entry : obj.entrySet()) {
                    String key = entry.getKey();
                    JsonValue propSchema = propSchemas.get(key);
                    if (propSchema != null) {
                        validateNode(entry.getValue(), propSchema, path + "." + key, errors);
                    }
                }
            }
        }

        // --- array constraints ---
        if (value.isArray()) {
            JsonValue itemsNode = schema.get("items");
            if (itemsNode != null && itemsNode.isObject()) {
                List<JsonValue> arr = value.asArray();
                for (int i = 0; i < arr.size(); i++) {
                    validateNode(arr.get(i), itemsNode, path + "[" + i + "]", errors);
                }
            }
        }
    }

    private static boolean matchesType(JsonValue value, String expectedType) {
        return switch (expectedType) {
            case "object"  -> value.isObject();
            case "array"   -> value.isArray();
            case "string"  -> value.isString();
            case "number"  -> value.isNumber();
            case "integer" -> value.isNumber() && value.isInteger();
            case "boolean" -> value.isBoolean();
            case "null"    -> value.isNull();
            default        -> true; // unknown type → pass
        };
    }

    private static boolean matchesEnum(JsonValue value, List<JsonValue> allowed) {
        for (JsonValue option : allowed) {
            if (valuesEqual(value, option)) return true;
        }
        return false;
    }

    private static boolean valuesEqual(JsonValue a, JsonValue b) {
        if (a.getType() != b.getType()) return false;
        return switch (a.getType()) {
            case STRING  -> a.asString().equals(b.asString());
            case NUMBER  -> a.asNumber() == b.asNumber();
            case BOOLEAN -> a.asBoolean() == b.asBoolean();
            case NULL    -> true;
            default      -> a.toString().equals(b.toString());
        };
    }
}
