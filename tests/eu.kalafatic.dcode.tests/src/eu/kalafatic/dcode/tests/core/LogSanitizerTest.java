package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.log.LogSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSanitizerTest {

    @Test
    void masksBearerTokens() {
        String in = "Authorization: Bearer sk-1234567890abcdef";
        String out = LogSanitizer.sanitize(in);
        assertFalse(out.contains("sk-1234567890abcdef"));
        assertTrue(out.contains("***"));
    }

    @Test
    void masksKeyValueSecrets() {
        String out = LogSanitizer.sanitize("api_key=verysecret123 password=hunter2");
        assertFalse(out.contains("verysecret123"));
        assertFalse(out.contains("hunter2"));
    }

    @Test
    void masksUriUserInfo() {
        String out = LogSanitizer.sanitize("http://user:pw@localhost:11434/api/chat");
        assertFalse(out.contains("pw@"));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String text = "Building module eu.kalafatic.dcode.core";
        assertEquals(text, LogSanitizer.sanitize(text));
    }
}
