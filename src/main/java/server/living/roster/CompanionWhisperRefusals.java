package server.living.roster;

import client.Character;
import server.agents.auth.AgentClaimGuard;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * What a companion whispers back when its owner's whisper reached no Agent (live 2026-10-10: Bossfriend whispered
 * Tamsin and heard nothing). The whisper bridge tells the claim rule of each refusal ({@link AgentClaimGuard.Refusal}):
 * on a live bench slice the bench session keeps the companion until a call ends the slice, and between loading and its
 * Agent running nobody takes the line. Only the owner hears it, in the companion's own voice (a hired citizen's
 * typing), at most once per living_world.COMPANION_WHISPER_REFUSAL_GAP_MS per companion; strangers, Residents nobody
 * owns and citizens get nothing, as before.
 */
final class CompanionWhisperRefusals {
    /** Kept by its live bench slice: "call" or "come back" ends the slice. */
    static final List<String> ON_THE_BENCH = List.of(
            "i'm off doing my own thing right now, call me back first.",
            "busy out here atm. call me back if u need me.",
            "can't from out here, call me back first.");
    /** Loaded, but its Agent is not running yet (a spawn or a relog in flight). */
    static final List<String> NOT_READY = List.of(
            "can't right now, try again in a sec.",
            "one sec, still getting my stuff together.",
            "hold on, gimme a sec.");

    private final LivingWorld world;
    private final LongSupplier clock;
    /** persona id -> when it last said one of these lines. */
    private final Map<String, Long> lastSaidMs = new ConcurrentHashMap<>();

    CompanionWhisperRefusals(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /**
     * The owner's whisper to {@code agent} (persona {@code personaId}) was refused for {@code refusal}; the caller has
     * checked that {@code owner} owns it. A KEPT companion speaks only while a live bench slice keeps it.
     */
    void refused(Character owner, Character agent, String personaId, AgentClaimGuard.Refusal refusal) {
        boolean benchLive = world.benchDuty() != null && world.benchDuty().live(personaId);
        if (refusal == AgentClaimGuard.Refusal.KEPT && !benchLive) {
            return;
        }
        Persona persona = world.content().persona(personaId);
        if (persona == null || !due(personaId)) {
            return;
        }
        List<String> lines = benchLive ? ON_THE_BENCH : NOT_READY;
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        HiredHands hands = world.hiredHands();
        world.residents().whisper(persona, owner, hands == null ? line : hands.voice(agent.getId(), line));
    }

    /** True (and remembered) when this companion has said none of these lines within the gap. */
    private boolean due(String personaId) {
        long now = clock.getAsLong();
        long gapMs = world.config().COMPANION_WHISPER_REFUSAL_GAP_MS;
        boolean[] due = {false};
        lastSaidMs.compute(personaId, (id, last) -> {
            if (last != null && now - last < gapMs) {
                return last;
            }
            due[0] = true;
            return now;
        });
        return due[0];
    }
}
