package server.living.story;

import client.Character;
import server.living.config.Content.SceneBeat;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.Locale;
import java.util.Set;

/**
 * The effects a scene may play (F6.2): a closed list, each one a packet this server already sends in stock play, so no
 * scene can send the client something it has not drawn before. Some avatar effects crash the v83 client
 * ({@code Effect/BasicEff.img/QuestAlert}, docs/LIVING_WORLD_GUIDE.md "Quest markers"), so no {@code showInfo},
 * {@code showIntro} or {@code showForeignInfo} path is here, no music change and no temporary NPC. Everything goes to
 * the scene's private map only (its owner and their companions).
 */
public enum SceneEffect {
    /** The party-quest clear: {@code quest/party/clear} and its sound (EventInstanceManager's clear). */
    CLEAR("clear"),
    /** The party-quest miss: {@code quest/party/wrong_kor} and its sound. */
    FAIL("fail"),
    /** A weather banner with a line of narration ({@code item} one of {@link #BANNER_ITEMS}, as dojang_Msg.js). */
    BANNER("banner"),
    /** The job-advance aura on the speaking companion ({@code showForeignEffect(id, 8)}, as Character.changeJob). */
    JOB_AURA("jobAura"),
    /** The quest-clear sparkle on the owner ({@code showSpecialEffect(9)}, as a stock quest's completion). */
    QUEST_CLEAR("questClear"),
    /** The screen shake (as the mob autonomy service's). */
    TREMBLE("tremble"),
    /** A countdown clock of {@code seconds} (as the 3rd-job event scripts). */
    CLOCK("clock");

    /** The weather items stock scripts show banners with (dojang_Msg.js, RescueGaga.js). */
    public static final Set<Integer> BANNER_ITEMS = Set.of(5120016, 5120024, 5120027);

    private final String key;

    SceneEffect(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The effect a beat names, or null when it names none on the list. */
    public static SceneEffect of(String name) {
        if (name == null) {
            return null;
        }
        for (SceneEffect effect : values()) {
            if (effect.key.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return effect;
            }
        }
        return null;
    }

    /** What a beat with this effect is missing, or null when it is whole. */
    public String problem(SceneBeat beat) {
        return switch (this) {
            case BANNER -> !BANNER_ITEMS.contains(beat.itemId()) ? "banner item must be one of " + BANNER_ITEMS
                    : beat.text() == null || beat.text().isBlank() ? "banner needs text" : null;
            case CLOCK -> beat.seconds() <= 0 ? "clock needs seconds" : null;
            default -> null;
        };
    }

    /** Plays it on the scene's map; {@code companion} wears the job aura, {@code owner} the quest-clear sparkle. */
    public void play(MapleMap map, SceneBeat beat, Character companion, Character owner) {
        switch (this) {
            case CLEAR -> {
                map.broadcastMessage(PacketCreator.showEffect("quest/party/clear"));
                map.broadcastMessage(PacketCreator.playSound("Party1/Clear"));
            }
            case FAIL -> {
                map.broadcastMessage(PacketCreator.showEffect("quest/party/wrong_kor"));
                map.broadcastMessage(PacketCreator.playSound("Party1/Failed"));
            }
            case BANNER -> map.broadcastMessage(PacketCreator.startMapEffect(beat.text(), beat.itemId(), true));
            case JOB_AURA -> {
                if (companion != null) {
                    map.broadcastMessage(PacketCreator.showForeignEffect(companion.getId(), 8));
                }
            }
            case QUEST_CLEAR -> {
                if (owner != null) {
                    owner.sendPacket(PacketCreator.showSpecialEffect(9));
                }
            }
            case TREMBLE -> map.broadcastMessage(PacketCreator.trembleEffect(1, 0));
            case CLOCK -> map.broadcastMessage(PacketCreator.getClock(beat.seconds()));
        }
    }

    /** Takes a banner down (the scene's next beat, or its end). */
    public static void clearBanner(MapleMap map) {
        map.broadcastMessage(PacketCreator.removeMapEffect());
    }
}
