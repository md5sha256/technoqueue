package io.github.md5sha256.technoqueue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueListenerTest {

    private static List<Pattern> patterns(String... regexes) {
        return List.of(regexes).stream()
                .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    @Test
    void noPatternsNeverMatches() {
        assertFalse(QueueListener.reasonMatches("Kicked for being AFK", List.of()));
    }

    @Test
    void emptyReasonNeverMatches() {
        assertFalse(QueueListener.reasonMatches("", patterns("afk")));
    }

    @Test
    void matchesSubstringUnanchored() {
        assertTrue(QueueListener.reasonMatches("You were kicked for being idle.", patterns("idle")));
    }

    @Test
    void matchIsCaseInsensitive() {
        assertTrue(QueueListener.reasonMatches("Disconnected: AFK timeout", patterns("afk")));
    }

    @Test
    void honoursRegexMetacharacters() {
        assertTrue(QueueListener.reasonMatches("kicked for inactivity", patterns("\\binactivity\\b")));
        assertFalse(QueueListener.reasonMatches("proactivity is good", patterns("\\binactivity\\b")));
    }

    @Test
    void nonMatchingReasonReturnsFalse() {
        assertFalse(QueueListener.reasonMatches("Server is restarting", patterns("afk", "idle")));
    }

    @Test
    void matchesAnyOfSeveralPatterns() {
        List<Pattern> several = patterns("afk", "idle", "inactiv");
        assertTrue(QueueListener.reasonMatches("kicked for inactivity", several));
    }
}
