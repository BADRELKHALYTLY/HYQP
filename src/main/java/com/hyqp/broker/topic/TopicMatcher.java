package com.hyqp.broker.topic;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Topic matching engine supporting three filter mechanisms:
 *
 *   - '+'              Single-level wildcard (matches exactly one level)
 *   - '#'              Multi-level wildcard (matches zero or more levels, must be last)
 *   - regex('pattern') Regex filter (matches one level against the given regex)
 *
 * Examples:
 *   factory/regex('line-[0-9]+')/temperature
 *       matches  factory/line-1/temperature
 *       matches  factory/line-42/temperature
 *       no match factory/zone-A/temperature
 *
 *   regex('sensor-[a-z]+')/+/data
 *       matches  sensor-abc/temp/data
 *       no match sensor-123/temp/data
 */
public final class TopicMatcher {

    private static final String REGEX_PREFIX = "regex('";
    private static final String REGEX_SUFFIX = "')";

    private TopicMatcher() {}

    /**
     * @param filter  the subscription/publish pattern (may contain +, # and regex('...'))
     * @param topic   the concrete topic (no wildcards)
     * @return true if the topic matches the filter
     */
    public static boolean matches(String filter, String topic) {
        String[] filterLevels = filter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);

        int fi = 0;
        int ti = 0;

        while (fi < filterLevels.length) {
            String f = filterLevels[fi];

            if ("#".equals(f)) {
                return true;
            }

            if (ti >= topicLevels.length) {
                return false;
            }

            if ("+".equals(f)) {
                fi++;
                ti++;
            } else if (isRegexLevel(f)) {
                String regex = extractRegex(f);
                if (regex == null || !matchesRegex(regex, topicLevels[ti])) {
                    return false;
                }
                fi++;
                ti++;
            } else {
                if (!f.equals(topicLevels[ti])) {
                    return false;
                }
                fi++;
                ti++;
            }
        }

        return ti == topicLevels.length;
    }

    /**
     * @return true if the filter contains wildcard or regex segments
     */
    public static boolean isWildcard(String filter) {
        return filter.contains("+") || filter.contains("#") || filter.contains(REGEX_PREFIX);
    }

    /**
     * @return true if this single level is a regex('...') expression
     */
    public static boolean isRegexLevel(String level) {
        return level.startsWith(REGEX_PREFIX) && level.endsWith(REGEX_SUFFIX);
    }

    /**
     * Extracts the regex pattern string from regex('...'), or null if malformed.
     */
    public static String extractRegex(String level) {
        if (!isRegexLevel(level)) return null;
        return level.substring(REGEX_PREFIX.length(), level.length() - REGEX_SUFFIX.length());
    }

    /**
     * Validates that a regex('...') expression contains a compilable pattern.
     * @return null if valid, or an error message if invalid
     */
    public static String validateRegex(String level) {
        String regex = extractRegex(level);
        if (regex == null) return "Malformed regex expression: " + level;
        if (regex.isEmpty()) return "Empty regex pattern in: " + level;
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException e) {
            return "Invalid regex '" + regex + "': " + e.getDescription();
        }
    }

    private static boolean matchesRegex(String regex, String value) {
        try {
            return Pattern.matches(regex, value);
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
