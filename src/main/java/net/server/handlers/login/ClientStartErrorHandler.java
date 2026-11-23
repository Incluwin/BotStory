package net.server.handlers.login;

import client.Client;
import net.PacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A v83 client that crashed sends its crash report the next time it starts (C->S 0x19, one string): the character,
 * the map, the exception and its address, the registers and the call stack. The server logs it, because the crash
 * itself shows here only as "Connection reset": this is what tells a missing WZ entry (an error box) from a real
 * fault, and which packet or map to look at. Anyone can connect to the login port, so a report is cut to
 * {@link #MAX_CHARS}, stripped to printable text, and taken once per address per {@link #COOLDOWN_MILLIS}.
 */
public final class ClientStartErrorHandler implements PacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ClientStartErrorHandler.class);
    static final int MAX_CHARS = 8_000;
    static final long COOLDOWN_MILLIS = 10_000;
    private static final int MAX_ADDRESSES = 1_000;

    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();

    @Override
    public boolean validateState(Client c) {
        return true; // sent before the login
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        if (p.available() < 2 || !accept(c.getRemoteAddress(), System.currentTimeMillis())) {
            return;
        }
        String report;
        try {
            report = clean(p.readString());
        } catch (RuntimeException e) {
            return; // not a string after all
        }
        if (!report.isBlank()) {
            log.warn("Client crash report from {} (a client sends it when it next starts):\n{}",
                    c.getRemoteAddress(), report);
        }
    }

    /** One report per address per cooldown; the table is emptied rather than grown past its cap. */
    boolean accept(String address, long now) {
        String key = address == null ? "" : address;
        Long last = lastReport.get(key);
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return false;
        }
        if (lastReport.size() >= MAX_ADDRESSES) {
            lastReport.clear();
        }
        lastReport.put(key, now);
        return true;
    }

    /** Printable ASCII, tabs and line breaks only (it goes into the log), at most {@link #MAX_CHARS}. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_CHARS));
        for (int i = 0; i < raw.length() && out.length() < MAX_CHARS; i++) {
            char ch = raw.charAt(i);
            if (ch == '\n' || ch == '\t' || (ch >= 0x20 && ch < 0x7F)) {
                out.append(ch);
            }
        }
        return out.toString().strip();
    }
}
