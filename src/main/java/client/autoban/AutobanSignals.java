package client.autoban;

import client.BotClient;
import client.Character;
import server.security.SecurityEventRuntime;
import server.security.SecurityEventType;
import server.security.SecuritySeverity;

import java.util.Map;

/**
 * Writes the AUTOBAN_SIGNAL security event for one anti-cheat signal. A human's signal is persisted, logged and, when
 * CRITICAL, alerted. An Agent's signal is a server bug, not cheating to review, so it stays in memory only: the GM
 * skill probe still reads it there, but it does not fill the security_event table or the SECURITY logs.
 */
final class AutobanSignals {
    private AutobanSignals() {
    }

    static void record(Character chr, SecuritySeverity severity, Map<String, String> evidence) {
        if (chr != null && chr.getClient() instanceof BotClient) {
            SecurityEventRuntime.recordInMemoryOnly(chr, SecurityEventType.AUTOBAN_SIGNAL, severity, evidence);
        } else {
            SecurityEventRuntime.record(chr, SecurityEventType.AUTOBAN_SIGNAL, severity, evidence);
        }
    }
}
