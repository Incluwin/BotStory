package server.agents.capabilities.expedition.cwkpq;

import client.Character;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Crimsonwood Keep sigils (CWKPQ plan B1): one per archetype in stages 2 and 3 (reactors 6109000-6109004) and among
 * stage 4's shuffled spots (6109016-6109020). A sigil turns only for a hit with one of its skills (Reactor.wz
 * activeSkillID; Reactor.hitReactor skips any other skill id), so a member can turn its archetype's sigil only once it
 * holds one of them at level 1 or more. A Crusader or White Knight under the shaping build has Charged Blow or Shout at
 * about Lv100, an Outlaw has Gaviota at about Lv96 (C0 results), so the archetype of a member's job is not enough.
 */
public final class AgentCwkpqSigils {
    /** The five archetypes, by job niche ((jobId / 100) % 10, as the stage-5 portals read it). */
    public enum Archetype {
        WARRIOR(1, "Warrior", 6_109_000, 6_109_016, List.of(1_111_008, 1_211_002, 1_311_003, 1_311_004),
                "Shout, Charged Blow, Dragon Fury: Spear or Dragon Fury: Pole Arm"),
        MAGICIAN(2, "Magician", 6_109_002, 6_109_018, List.of(2_001_005, 2_111_002, 2_211_002, 2_321_003),
                "Magic Claw, Explosion, Ice Strike or Bahamut"),
        BOWMAN(3, "Bowman", 6_109_001, 6_109_017, List.of(3_111_005, 3_111_006, 3_211_005, 3_211_006, 3_221_001),
                "Silver Hawk, Strafe, Golden Eagle or Piercing Arrow"),
        THIEF(4, "Thief", 6_109_003, 6_109_019, List.of(4_111_002, 4_111_005, 4_211_001, 4_211_004, 4_211_006),
                "Shadow Partner, Avenger, Chakra, Band of Thieves or Meso Explosion"),
        PIRATE(5, "Pirate", 6_109_004, 6_109_020, List.of(5_001_001, 5_111_005, 5_121_001, 5_211_002, 5_221_004),
                "Flash Fist, Transformation, Dragon Strike, Gaviota or Rapid Fire");

        private final int niche;
        private final String label;
        private final int sigilReactorId;
        private final int witSigilReactorId;
        private final List<Integer> skillIds;
        private final String skillNames;

        Archetype(int niche, String label, int sigilReactorId, int witSigilReactorId, List<Integer> skillIds,
                  String skillNames) {
            this.niche = niche;
            this.label = label;
            this.sigilReactorId = sigilReactorId;
            this.witSigilReactorId = witSigilReactorId;
            this.skillIds = List.copyOf(skillIds);
            this.skillNames = skillNames;
        }

        /** The job niche, (jobId / 100) % 10. */
        public int niche() {
            return niche;
        }

        /** "Warrior", as Jack names it. */
        public String label() {
            return label;
        }

        /** The stage 2 and stage 3 sigil (6109000-6109004). */
        public int sigilReactorId() {
            return sigilReactorId;
        }

        /** The stage 4 sigil (6109016-6109020). */
        public int witSigilReactorId() {
            return witSigilReactorId;
        }

        /** The skills that turn this archetype's sigils. */
        public List<Integer> skillIds() {
            return skillIds;
        }

        /** "Shout, Charged Blow, Dragon Fury: Spear or Dragon Fury: Pole Arm": the skills by their String.wz names. */
        public String skillNames() {
            return skillNames;
        }
    }

    /**
     * Every sigil's hit area from its position (Reactor.wz lt -125,-19 and rb 123,122, the same for all ten): a cast
     * whose area meets it reaches the sigil, from up to 122 px below it, as a player hits one hanging over the floor.
     */
    public static final int AREA_LEFT = -125;
    public static final int AREA_TOP = -19;
    public static final int AREA_RIGHT = 123;
    public static final int AREA_BOTTOM = 122;

    private AgentCwkpqSigils() {
    }

    /** The hit area of a sigil standing at {@code position}. */
    public static java.awt.Rectangle area(java.awt.Point position) {
        return new java.awt.Rectangle(position.x + AREA_LEFT, position.y + AREA_TOP, AREA_RIGHT - AREA_LEFT,
                AREA_BOTTOM - AREA_TOP);
    }

    /** The archetype of a job id; null for a Beginner, a GM or a job outside the five niches. */
    public static Archetype archetypeOf(int jobId) {
        if (jobId < 100 || jobId >= 600) {
            return null;
        }
        int niche = (jobId / 100) % 10;
        for (Archetype archetype : Archetype.values()) {
            if (archetype.niche == niche) {
                return archetype;
            }
        }
        return null;
    }

    /** The archetype whose sigil {@code member} can turn now (its job's, holding one of its skills), else null. */
    public static Archetype turns(Character member) {
        Archetype archetype = member == null || member.getJob() == null ? null : archetypeOf(member.getJob().getId());
        return archetype != null && bestSkill(member, archetype) != 0 ? archetype : null;
    }

    /** The first of {@code archetype}'s sigil skills {@code member} holds at level 1 or more; 0 for none. */
    public static int bestSkill(Character member, Archetype archetype) {
        if (member == null || archetype == null) {
            return 0;
        }
        for (int skillId : archetype.skillIds) {
            if (member.getSkillLevel(skillId) > 0) {
                return skillId;
            }
        }
        return 0;
    }

    /** How many of {@code members} can turn each archetype's sigil; every archetype is a key, 0 when nobody can. */
    public static Map<Archetype, Integer> coverage(Collection<Character> members) {
        Map<Archetype, Integer> counts = new EnumMap<>(Archetype.class);
        for (Archetype archetype : Archetype.values()) {
            counts.put(archetype, 0);
        }
        for (Character member : members) {
            Archetype archetype = turns(member);
            if (archetype != null) {
                counts.merge(archetype, 1, Integer::sum);
            }
        }
        return counts;
    }
}
