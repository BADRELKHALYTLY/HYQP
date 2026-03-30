package com.hyqp.broker.topic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicMatcherTest {

    // --- Exact match ---

    @Test
    void exactMatch() {
        assertTrue(TopicMatcher.matches("sensors/temp", "sensors/temp"));
    }

    @Test
    void exactNoMatch() {
        assertFalse(TopicMatcher.matches("sensors/temp", "sensors/humidity"));
    }

    // --- Single-level wildcard (+) ---

    @Test
    void plusMatchesOneLevel() {
        assertTrue(TopicMatcher.matches("sensors/+/data", "sensors/temp/data"));
        assertTrue(TopicMatcher.matches("sensors/+/data", "sensors/humidity/data"));
    }

    @Test
    void plusDoesNotMatchMultipleLevels() {
        assertFalse(TopicMatcher.matches("sensors/+/data", "sensors/temp/sub/data"));
    }

    @Test
    void plusAtEnd() {
        assertTrue(TopicMatcher.matches("sensors/+", "sensors/temp"));
        assertTrue(TopicMatcher.matches("sensors/+", "sensors/humidity"));
    }

    @Test
    void plusDoesNotMatchZeroLevels() {
        assertFalse(TopicMatcher.matches("sensors/+/data", "sensors/data"));
    }

    @Test
    void plusAtStart() {
        assertTrue(TopicMatcher.matches("+/temp", "sensors/temp"));
        assertTrue(TopicMatcher.matches("+/temp", "devices/temp"));
    }

    @Test
    void multiplePlus() {
        assertTrue(TopicMatcher.matches("+/+/data", "sensors/temp/data"));
        assertFalse(TopicMatcher.matches("+/+/data", "sensors/temp/sub/data"));
    }

    // --- Multi-level wildcard (#) ---

    @Test
    void hashMatchesEverything() {
        assertTrue(TopicMatcher.matches("#", "sensors"));
        assertTrue(TopicMatcher.matches("#", "sensors/temp"));
        assertTrue(TopicMatcher.matches("#", "sensors/temp/data"));
        assertTrue(TopicMatcher.matches("#", "a/b/c/d/e"));
    }

    @Test
    void hashAtEnd() {
        assertTrue(TopicMatcher.matches("sensors/#", "sensors"));
        assertTrue(TopicMatcher.matches("sensors/#", "sensors/temp"));
        assertTrue(TopicMatcher.matches("sensors/#", "sensors/temp/data"));
        assertTrue(TopicMatcher.matches("sensors/#", "sensors/a/b/c"));
    }

    @Test
    void hashDoesNotMatchDifferentPrefix() {
        assertFalse(TopicMatcher.matches("sensors/#", "devices/temp"));
    }

    @Test
    void hashAfterPlus() {
        assertTrue(TopicMatcher.matches("+/temp/#", "sensors/temp"));
        assertTrue(TopicMatcher.matches("+/temp/#", "sensors/temp/data"));
        assertTrue(TopicMatcher.matches("+/temp/#", "devices/temp/a/b"));
    }

    // --- Edge cases ---

    @Test
    void topicLongerThanFilter() {
        assertFalse(TopicMatcher.matches("sensors/temp", "sensors/temp/data"));
    }

    @Test
    void filterLongerThanTopic() {
        assertFalse(TopicMatcher.matches("sensors/temp/data", "sensors/temp"));
    }

    @Test
    void singleLevel() {
        assertTrue(TopicMatcher.matches("sensors", "sensors"));
        assertTrue(TopicMatcher.matches("+", "sensors"));
        assertTrue(TopicMatcher.matches("#", "sensors"));
    }

    // --- Regex single-level filter: regex('...') ---

    @Test
    void regexMatchesSingleLevel() {
        assertTrue(TopicMatcher.matches("factory/regex('line-[0-9]+')/temperature", "factory/line-1/temperature"));
        assertTrue(TopicMatcher.matches("factory/regex('line-[0-9]+')/temperature", "factory/line-42/temperature"));
    }

    @Test
    void regexRejectsNonMatching() {
        assertFalse(TopicMatcher.matches("factory/regex('line-[0-9]+')/temperature", "factory/zone-A/temperature"));
    }

    @Test
    void regexDoesNotMatchMultipleLevels() {
        assertFalse(TopicMatcher.matches("factory/regex('line-[0-9]+')/temperature", "factory/line-1/sub/temperature"));
    }

    @Test
    void regexAtStart() {
        assertTrue(TopicMatcher.matches("regex('sensor-[a-z]+')/temp", "sensor-abc/temp"));
        assertFalse(TopicMatcher.matches("regex('sensor-[a-z]+')/temp", "sensor-123/temp"));
    }

    @Test
    void regexAtEnd() {
        assertTrue(TopicMatcher.matches("factory/regex('[A-Z]{3}')", "factory/NYC"));
        assertFalse(TopicMatcher.matches("factory/regex('[A-Z]{3}')", "factory/new-york"));
    }

    @Test
    void multipleRegexLevels() {
        assertTrue(TopicMatcher.matches(
            "regex('building-[0-9]+')/regex('floor-[0-9]+')/temp",
            "building-5/floor-12/temp"));
        assertFalse(TopicMatcher.matches(
            "regex('building-[0-9]+')/regex('floor-[0-9]+')/temp",
            "building-5/lobby/temp"));
    }

    @Test
    void regexCombinedWithPlus() {
        assertTrue(TopicMatcher.matches("regex('factory-[0-9]+')/+/temperature", "factory-1/line-A/temperature"));
        assertTrue(TopicMatcher.matches("regex('factory-[0-9]+')/+/temperature", "factory-99/anything/temperature"));
    }

    @Test
    void regexCombinedWithHash() {
        assertTrue(TopicMatcher.matches("regex('zone-[A-Z]')/#", "zone-A"));
        assertTrue(TopicMatcher.matches("regex('zone-[A-Z]')/#", "zone-B/temp"));
        assertTrue(TopicMatcher.matches("regex('zone-[A-Z]')/#", "zone-C/sensors/temp/data"));
        assertFalse(TopicMatcher.matches("regex('zone-[A-Z]')/#", "zone-AB/temp"));
    }

    // --- isWildcard ---

    @Test
    void isWildcard() {
        assertTrue(TopicMatcher.isWildcard("sensors/+"));
        assertTrue(TopicMatcher.isWildcard("sensors/#"));
        assertTrue(TopicMatcher.isWildcard("+/+/#"));
        assertTrue(TopicMatcher.isWildcard("factory/regex('line-[0-9]+')/temp"));
        assertFalse(TopicMatcher.isWildcard("sensors/temp"));
    }

    // --- isRegexLevel ---

    @Test
    void isRegexLevel() {
        assertTrue(TopicMatcher.isRegexLevel("regex('line-[0-9]+')"));
        assertFalse(TopicMatcher.isRegexLevel("regex(line)"));
        assertFalse(TopicMatcher.isRegexLevel("+"));
        assertFalse(TopicMatcher.isRegexLevel("sensors"));
    }

    // --- validateRegex ---

    @Test
    void validateRegexValid() {
        assertNull(TopicMatcher.validateRegex("regex('line-[0-9]+')"));
        assertNull(TopicMatcher.validateRegex("regex('[A-Z]{3}')"));
    }

    @Test
    void validateRegexInvalid() {
        assertNotNull(TopicMatcher.validateRegex("regex('[invalid')"));
    }
}
