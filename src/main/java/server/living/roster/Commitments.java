package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Commitments (docs/BOT_MANAGEMENT_PLAN.md, "Core concepts"): an in-memory lock that ties companions to one event
 * instance or expedition, its key. While a companion is committed the roster refuses to bench it, swap it or send it
 * on an errand ("after the run"), the party sync leaves it alone (its party events wait), and a contract walk-out waits.
 * A commitment ends when its member leaves the run ({@link #releaseMember}) or the run ends ({@link #releaseKey}); each
 * listener then hears which members were let go, on the releasing thread, and hands any roster work to companion-ops.
 * Owned by LivingWorld; nothing survives a restart.
 */
public final class Commitments {
    private static final Logger log = LoggerFactory.getLogger(Commitments.class);

    /** A companion's character tied to {@code key} for its owner. */
    public record Member(int characterId, int ownerId, String key) { }

    /** A commitment ended for these members of {@code key} (all of it after {@link #releaseKey}, one after a member left). */
    public record Ended(String key, List<Member> members) { }

    private final Map<Integer, Member> byCharacter = new ConcurrentHashMap<>();
    private final List<Consumer<Ended>> listeners = new CopyOnWriteArrayList<>();

    /** Ties the character to {@code key}; false when it is committed to another key (the same key is kept as it is). */
    public synchronized boolean commit(String key, int ownerId, int characterId) {
        if (key == null || characterId <= 0) {
            return false;
        }
        Member held = byCharacter.get(characterId);
        if (held != null) {
            return held.key().equals(key);
        }
        byCharacter.put(characterId, new Member(characterId, ownerId, key));
        return true;
    }

    public boolean isCommitted(int characterId) {
        return byCharacter.containsKey(characterId);
    }

    /** True while any of the owner's companions is tied to a run. */
    public boolean ownerCommitted(int ownerId) {
        for (Member member : byCharacter.values()) {
            if (member.ownerId() == ownerId) {
                return true;
            }
        }
        return false;
    }

    /** The key the character is committed to, or null. */
    public String keyOf(int characterId) {
        Member held = byCharacter.get(characterId);
        return held == null ? null : held.key();
    }

    /** The members committed to {@code key}. */
    public List<Member> members(String key) {
        List<Member> out = new ArrayList<>();
        for (Member member : byCharacter.values()) {
            if (member.key().equals(key)) {
                out.add(member);
            }
        }
        return out;
    }

    /** The member left the run: its commitment ends. False when it was not committed. */
    public boolean releaseMember(int characterId) {
        Member released;
        synchronized (this) {
            released = byCharacter.remove(characterId);
        }
        if (released == null) {
            return false;
        }
        fire(new Ended(released.key(), List.of(released)));
        return true;
    }

    /** The run ended: every commitment to {@code key} ends. Returns the members let go. */
    public List<Member> releaseKey(String key) {
        List<Member> released = new ArrayList<>();
        synchronized (this) {
            for (Member member : members(key)) {
                if (byCharacter.remove(member.characterId(), member)) {
                    released.add(member);
                }
            }
        }
        if (!released.isEmpty()) {
            fire(new Ended(key, List.copyOf(released)));
        }
        return released;
    }

    /** Hears every commitment that ends (the tracks' deferred work: party reconciliation, walk-outs, holds). */
    public void addListener(Consumer<Ended> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void fire(Ended ended) {
        for (Consumer<Ended> listener : listeners) {
            try {
                listener.accept(ended);
            } catch (RuntimeException failure) {
                log.warn("commitment listener failed for {}", ended.key(), failure);
            }
        }
    }
}
