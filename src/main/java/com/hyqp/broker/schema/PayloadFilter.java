package com.hyqp.broker.schema;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates a filter expression against a JSON payload.
 *
 * Filter format — a JSON object where each key is a field name
 * and the value is an object of operators:
 *
 *   {
 *     "value": {"$gt": 30, "$lt": 80},
 *     "unit":  {"$eq": "celsius"},
 *     "status": {"$in": ["active", "warning"]},
 *     "name":  {"$contains": "sensor"},
 *     "id":    {"$regex": "^S[0-9]+$"}
 *   }
 *
 * Supported operators:
 *   $eq       — equals
 *   $neq      — not equals
 *   $gt       — greater than (numbers)
 *   $gte      — greater than or equal (numbers)
 *   $lt       — less than (numbers)
 *   $lte      — less than or equal (numbers)
 *   $in       — value is in a list
 *   $nin      — value is NOT in a list
 *   $contains — string contains substring
 *   $regex    — string matches regex
 *
 * All field conditions are ANDed together.
 * All operators on the same field are ANDed together.
 */
public final class PayloadFilter {

    private PayloadFilter() {}

    /**
     * @return true if the payload matches all filter conditions
     */
    public static boolean matches(JsonValue payload, JsonValue filter) {
        if (!payload.isObject() || !filter.isObject()) {
            return true; // no filtering possible on non-objects
        }

        Map<String, JsonValue> filterFields = filter.asObject();

        for (var entry : filterFields.entrySet()) {
            String fieldName = entry.getKey();
            JsonValue operators = entry.getValue();

            JsonValue fieldValue = payload.get(fieldName);

            // Field missing in payload → condition fails
            if (fieldValue == null) {
                return false;
            }

            if (!operators.isObject()) {
                // Direct value comparison: {"field": "value"} shorthand for $eq
                if (!valuesEqual(fieldValue, operators)) {
                    return false;
                }
                continue;
            }

            // Evaluate each operator
            for (var op : operators.asObject().entrySet()) {
                if (!evaluateOperator(op.getKey(), op.getValue(), fieldValue)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean evaluateOperator(String op, JsonValue expected, JsonValue actual) {
        return switch (op) {
            case "$eq"  -> valuesEqual(actual, expected);
            case "$neq" -> !valuesEqual(actual, expected);

            case "$gt"  -> actual.isNumber() && expected.isNumber() && actual.asNumber() > expected.asNumber();
            case "$gte" -> actual.isNumber() && expected.isNumber() && actual.asNumber() >= expected.asNumber();
            case "$lt"  -> actual.isNumber() && expected.isNumber() && actual.asNumber() < expected.asNumber();
            case "$lte" -> actual.isNumber() && expected.isNumber() && actual.asNumber() <= expected.asNumber();

            case "$in"  -> expected.isArray() && isIn(actual, expected);
            case "$nin" -> expected.isArray() && !isIn(actual, expected);

            case "$contains" -> actual.isString() && expected.isString()
                    && actual.asString().contains(expected.asString());

            case "$regex" -> {
                if (!actual.isString() || !expected.isString()) yield false;
                try {
                    yield Pattern.matches(expected.asString(), actual.asString());
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }

            default -> true; // unknown operator → pass (forward compatibility)
        };
    }

    private static boolean isIn(JsonValue value, JsonValue array) {
        for (JsonValue option : array.asArray()) {
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
