package server.living.config;

import server.agents.capabilities.townlife.AgentTownLifeProfile;
import server.agents.capabilities.townlife.AgentTownLifeProfileRepository;
import server.knowledge.EntityKind;
import server.knowledge.KnowledgeLint;
import server.living.config.Content.Arc;
import server.living.config.Content.Chapter;
import server.living.config.Content.Contract;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Rumor;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-reference checks over a loaded {@link LivingContent}. Findings prefixed {@code ERROR}
 * make {@link #hasErrors(List)} true; the rest are warnings (flags never read, etc.). When server knowledge is
 * available ({@link KnowledgeLint#current()}), item, mob, map and NPC ids are checked against it too.
 */
public final class ContentLint {
    private static final Map<String, AgentTownLifeProfile> PROFILES = profilesById();
    /** TownLife profile ids shipped in resources/agents/town-life (its index.json): the towns a persona can live in. */
    public static final Set<String> KNOWN_TOWNS = PROFILES.keySet();

    private ContentLint() {
    }

    public static boolean hasErrors(List<String> findings) {
        return findings.stream().anyMatch(finding -> finding.startsWith("ERROR"));
    }

    public static List<String> check(LivingContent content) {
        KnowledgeLint knowledge = KnowledgeLint.current();
        List<String> out = new ArrayList<>();
        Set<String> flagsSet = new LinkedHashSet<>();
        Set<String> flagsRead = new LinkedHashSet<>();

        for (Persona persona : content.personas().values()) {
            String where = persona.file() + " persona " + persona.id();
            if (LivingContent.isHire(persona.id())) {
                out.add("ERROR " + where + ": '" + LivingContent.HIRE_PREFIX + "' ids are hired citizens, never content");
            }
            if (!KNOWN_TOWNS.contains(persona.homeTown())) {
                out.add("ERROR " + where + ": unknown home town '" + persona.homeTown() + "'");
            }
            for (Content.RoutineSlot slot : persona.routine()) {
                String problem = unresolvedSpot(persona.homeTown(), slot.spot());
                if (problem != null) {
                    out.add("ERROR " + where + ": routine " + problem);
                }
            }
            if (persona.contract() != null && content.contract(persona.contract()) == null) {
                out.add("ERROR " + where + ": contract '" + persona.contract() + "' not found");
            }
            if (persona.recruitable() && persona.contract() == null) {
                out.add("ERROR " + where + ": recruitable personas need a contract");
            }
            if (persona.arc() != null) {
                Arc arc = content.arc(persona.arc());
                if (arc == null) {
                    out.add("ERROR " + where + ": arc '" + persona.arc() + "' not found");
                } else if (!arc.persona().equals(persona.id())) {
                    out.add("ERROR " + where + ": arc '" + arc.id() + "' belongs to persona " + arc.persona());
                }
            }
            Content.Look look = persona.look();
            if (look.gender() != null && !look.gender().equals("male") && !look.gender().equals("female")) {
                out.add("ERROR " + where + ": look.gender must be male or female, not '" + look.gender() + "'");
            }
            if (look.skin() > 11 || look.skin() < -1) {
                out.add("ERROR " + where + ": look.skin " + look.skin() + " is not a skin color (0-11)");
            }
            if (look.face() != 0 && (look.face() < 20000 || look.face() >= 22000)) {
                out.add("ERROR " + where + ": look.face " + look.face() + " is not a face id (20000-21999)");
            }
            if (look.hair() != 0 && (look.hair() < 30000 || look.hair() >= 35000)) {
                out.add("ERROR " + where + ": look.hair " + look.hair() + " is not a hair id (30000-34999)");
            }
            if (persona.loadout() != null && content.loadout(persona.loadout()) == null) {
                out.add("ERROR " + where + ": loadout '" + persona.loadout() + "' not found");
            }
            for (String questId : persona.questLines()) {
                Quest quest = content.quest(questId);
                if (quest == null) {
                    out.add("ERROR " + where + ": quest line '" + questId + "' not found");
                } else if (!quest.persona().equals(persona.id())) {
                    out.add("ERROR " + where + ": quest '" + questId + "' is given by " + quest.persona());
                }
            }
            for (String rumorId : persona.rumors()) {
                Rumor rumor = content.rumor(rumorId);
                if (rumor == null) {
                    out.add("ERROR " + where + ": rumor '" + rumorId + "' not found");
                } else if (!rumor.participants().contains(persona.id())) {
                    out.add("WARN " + where + ": listed rumor '" + rumorId + "' does not include this persona");
                }
            }
            for (Content.Relationship relationship : persona.relationships()) {
                if (authored(content, relationship.persona()) == null) {
                    out.add("WARN " + where + ": relationship to unknown persona '" + relationship.persona() + "'");
                }
            }
            if (persona.lines("greet").isEmpty()) {
                out.add("WARN " + where + ": no greet lines; the generic catalog will be used");
            }
        }

        for (Quest quest : content.quests().values()) {
            String where = quest.file() + " quest " + quest.id();
            Persona giver = authored(content, quest.persona());
            if (giver == null) {
                out.add("ERROR " + where + ": persona '" + quest.persona() + "' not found");
            } else if (!giver.questLines().contains(quest.id())) {
                out.add("WARN " + where + ": not listed in " + giver.id() + ".questLines (never offered)");
            }
            flagsRead.addAll(quest.requires().flags());
            flagsRead.addAll(quest.requires().notFlags());
            for (String completed : quest.requires().questsCompleted()) {
                if (content.quest(completed) == null) {
                    out.add("ERROR " + where + ": requires unknown quest '" + completed + "'");
                }
            }
            flagsSet.addAll(quest.rewards().flags());
            flagsSet.addAll(quest.rewards().worldFlags());
            for (Step step : quest.steps()) {
                String stepWhere = where + " step " + step.id();
                if (step.persona() != null && authored(content, step.persona()) == null) {
                    out.add("ERROR " + stepWhere + ": persona '" + step.persona() + "' not found");
                }
                if (step.with() != null && authored(content, step.with()) == null) {
                    out.add("ERROR " + stepWhere + ": with persona '" + step.with() + "' not found");
                }
                if (step.type() == StepType.DELIVER && !step.to().startsWith("npc:")
                        && authored(content, step.to()) == null) {
                    out.add("ERROR " + stepWhere + ": deliver target '" + step.to() + "' is neither a persona nor npc:<id>");
                }
                if (step.type() == StepType.HEAR_RUMOR && content.rumor(step.rumorId()) == null) {
                    out.add("ERROR " + stepWhere + ": rumor '" + step.rumorId() + "' not found");
                }
                if (step.type() == StepType.HEAR_RUMOR) {
                    Rumor rumor = content.rumor(step.rumorId());
                    if (rumor != null && rumor.setsFlagOnListeners() == null) {
                        out.add("ERROR " + stepWhere + ": rumor '" + step.rumorId() + "' sets no listener flag");
                    }
                }
            }
            int friendship = quest.requires().minFriendship();
            if (friendship < 0 || friendship > server.living.bond.Friendship.MAX_LEVEL) {
                out.add("ERROR " + where + ": requires.friendship " + friendship + " is not a level 0-"
                        + server.living.bond.Friendship.MAX_LEVEL);
            }
            if (!quest.rewards().gear().isEmpty()) {
                Step last = quest.steps().isEmpty() ? null : quest.steps().get(quest.steps().size() - 1);
                boolean handedOver = last != null && (last.type() == StepType.TALK
                        || last.type() == StepType.DELIVER && quest.persona().equals(last.to()));
                if (!handedOver) {
                    out.add("ERROR " + where + ": a quest with gear must end in a TALK or a DELIVER to its giver"
                            + " (the turn-in checks the player has room for it)");
                }
                for (Content.GearReward gear : quest.rewards().gear()) {
                    for (Content.ScrollRecipe line : gear.scrolls()) {
                        boolean count = line.count() >= 1 || line.count() == Content.ScrollRecipe.REST;
                        if (!count || line.success() != 10 && line.success() != 60 && line.success() != 100) {
                            out.add("ERROR " + where + ": gear scrolls need a success of 10, 60 or 100 and a count of at"
                                    + " least 1, or none for the rest (got " + line.success() + "% x" + line.count() + ")");
                        }
                    }
                }
            }
            if (quest.onComplete() != null) {
                Arc arc = content.arc(quest.onComplete().arc());
                if (arc == null) {
                    out.add("ERROR " + where + ": onComplete arc '" + quest.onComplete().arc() + "' not found");
                } else if (arc.chapter(quest.onComplete().to()) == null) {
                    out.add("ERROR " + where + ": onComplete chapter '" + quest.onComplete().to() + "' not in arc " + arc.id());
                }
            }
        }

        for (Contract contract : content.contracts().values()) {
            String where = contract.file() + " contract " + contract.id();
            flagsRead.addAll(contract.offerWhen().flags());
            if (contract.offerWhen().arc() != null) {
                Arc arc = content.arc(contract.offerWhen().arc());
                if (arc == null) {
                    out.add("ERROR " + where + ": offerWhen arc '" + contract.offerWhen().arc() + "' not found");
                } else if (arc.chapter(contract.offerWhen().chapterAtLeast()) == null) {
                    out.add("ERROR " + where + ": offerWhen chapter '" + contract.offerWhen().chapterAtLeast() + "' not in arc");
                }
            }
            boolean priced = contract.price().meso() > 0 || !contract.price().items().isEmpty()
                    || contract.price().proof() != null;
            if (!priced) {
                out.add("WARN " + where + ": contract has no price; the persona joins for free");
            }
            boolean used = LivingContent.HIRED_HAND_CONTRACT.equals(contract.id()) // every hired citizen's
                    || content.personas().values().stream().anyMatch(p -> contract.id().equals(p.contract()))
                    || content.arcs().values().stream().flatMap(a -> a.chapters().stream())
                    .anyMatch(c -> contract.id().equals(c.unlockContract()));
            if (!used) {
                out.add("WARN " + where + ": contract is not referenced by any persona or arc");
            }
        }

        for (Arc arc : content.arcs().values()) {
            String where = arc.file() + " arc " + arc.id();
            Persona owner = authored(content, arc.persona());
            if (owner == null) {
                out.add("ERROR " + where + ": persona '" + arc.persona() + "' not found");
            } else if (!arc.id().equals(owner.arc())) {
                out.add("WARN " + where + ": persona " + owner.id() + " does not declare this arc");
            }
            Set<String> chapterIds = new LinkedHashSet<>();
            for (Chapter chapter : arc.chapters()) {
                if (!chapterIds.add(chapter.id())) {
                    out.add("ERROR " + where + ": duplicate chapter '" + chapter.id() + "'");
                }
                if (chapter.enterWhen() != null) {
                    flagsRead.addAll(chapter.enterWhen().flags());
                    if (chapter.enterWhen().rumorPerformed() != null
                            && content.rumor(chapter.enterWhen().rumorPerformed()) == null) {
                        out.add("ERROR " + where + " chapter " + chapter.id() + ": rumor '"
                                + chapter.enterWhen().rumorPerformed() + "' not found");
                    }
                }
                for (String questId : chapter.unlockQuestLines()) {
                    if (content.quest(questId) == null) {
                        out.add("ERROR " + where + " chapter " + chapter.id() + ": unlock quest '" + questId + "' not found");
                    }
                }
                for (String rumorId : chapter.unlockRumors()) {
                    if (content.rumor(rumorId) == null) {
                        out.add("ERROR " + where + " chapter " + chapter.id() + ": unlock rumor '" + rumorId + "' not found");
                    }
                }
                if (chapter.unlockContract() != null && content.contract(chapter.unlockContract()) == null) {
                    out.add("ERROR " + where + " chapter " + chapter.id() + ": unlock contract '" + chapter.unlockContract() + "' not found");
                }
                if (chapter.loadout() != null && content.loadout(chapter.loadout()) == null) {
                    out.add("ERROR " + where + " chapter " + chapter.id() + ": loadout '" + chapter.loadout() + "' not found");
                }
                for (Content.RoutineSlot slot : chapter.routineOverride()) {
                    String problem = owner == null ? null : unresolvedSpot(owner.homeTown(), slot.spot());
                    if (problem != null) {
                        out.add("ERROR " + where + " chapter " + chapter.id() + ": routine " + problem);
                    }
                }
            }
        }

        for (Rumor rumor : content.rumors().values()) {
            String where = rumor.file() + " rumor " + rumor.id();
            if (!KNOWN_TOWNS.contains(rumor.town())) {
                out.add("ERROR " + where + ": unknown town '" + rumor.town() + "'");
            }
            String spotProblem = unresolvedSpot(rumor.town(), rumor.spot());
            if (spotProblem != null) {
                out.add("ERROR " + where + ": " + spotProblem);
            }
            if (rumor.participants().size() < 2) {
                out.add("ERROR " + where + ": a rumor needs two participants");
            }
            for (String participant : rumor.participants()) {
                Persona persona = authored(content, participant);
                if (persona == null) {
                    out.add("ERROR " + where + ": participant '" + participant + "' not found");
                } else if (!persona.homeTown().equals(rumor.town())) {
                    out.add("WARN " + where + ": participant " + participant + " lives in " + persona.homeTown()
                            + ", rumor plays in " + rumor.town());
                }
            }
            for (Content.RumorLine line : rumor.lines()) {
                if (!rumor.participants().contains(line.by())) {
                    out.add("ERROR " + where + ": line by '" + line.by() + "' who is not a participant");
                }
            }
            if (rumor.serverHours().isEmpty()) {
                out.add("ERROR " + where + ": when.serverHours is empty");
            }
            if (rumor.requiresArc() != null) {
                Arc arc = content.arc(rumor.requiresArc().arc());
                if (arc == null || arc.chapter(rumor.requiresArc().chapterAtLeast()) == null) {
                    out.add("ERROR " + where + ": requiresArc references an unknown arc or chapter");
                }
            }
            if (rumor.setsFlagOnListeners() != null) {
                flagsSet.add(rumor.setsFlagOnListeners());
            }
        }

        for (String flag : flagsRead) {
            if (!flagsSet.contains(flag)) {
                out.add("WARN flag '" + flag + "' is read but never set");
            }
        }
        for (String flag : flagsSet) {
            if (!flagsRead.contains(flag)) {
                out.add("WARN flag '" + flag + "' is set but never read");
            }
        }
        Set<String> canonicals = new LinkedHashSet<>();
        for (Content.Phrase phrase : content.phrases()) {
            if (!canonicals.add(phrase.canonical())) {
                out.add("ERROR command-phrases: duplicate canonical '" + phrase.canonical() + "'");
            }
            if (phrase.aliases().isEmpty()) {
                out.add("ERROR command-phrases: '" + phrase.canonical() + "' has no aliases");
            }
        }
        Set<Integer> logIds = new LinkedHashSet<>();
        for (var entry : content.questLogIds().entrySet()) {
            String where = ContentLoader.QUEST_LOG_IDS_FILE + " " + entry.getKey();
            if (content.quest(entry.getKey()) == null) {
                out.add("WARN " + where + ": no such quest (stale id " + entry.getValue() + ")");
            }
            if (entry.getValue() < QUEST_LOG_ID_MIN || entry.getValue() > QUEST_LOG_ID_MAX) {
                out.add("ERROR " + where + ": quest-log id " + entry.getValue() + " outside " + QUEST_LOG_ID_MIN + "-" + QUEST_LOG_ID_MAX);
            }
            if (!logIds.add(entry.getValue())) {
                out.add("ERROR " + where + ": quest-log id " + entry.getValue() + " used twice");
            }
        }
        if (!content.questLogIds().isEmpty()) {
            for (Quest quest : content.quests().values()) {
                if (content.questLogId(quest.id()) == 0) {
                    out.add("WARN " + quest.file() + " quest " + quest.id() + ": no quest-log id; run tools/living-world-questlog.bat");
                }
            }
        }
        for (Content.Loadout loadout : content.loadouts().values()) {
            for (Map.Entry<String, List<Integer>> slot : loadout.outfit().entrySet()) {
                List<Integer> prefixes = OUTFIT_SLOTS.get(slot.getKey());
                if (prefixes == null) {
                    out.add("ERROR loadouts.yaml loadout " + loadout.id() + ": unknown outfit slot '" + slot.getKey()
                            + "' (use " + String.join(", ", OUTFIT_SLOTS.keySet()) + ")");
                    continue;
                }
                for (int itemId : slot.getValue()) {
                    if (itemId != 0 && !prefixes.contains(itemId / 10000)) {
                        out.add("ERROR loadouts.yaml loadout " + loadout.id() + ": outfit " + slot.getKey() + " item "
                                + (knowledge == null ? String.valueOf(itemId) : knowledge.label(EntityKind.ITEM, itemId))
                                + " is not a " + slot.getKey() + " item");
                    }
                }
            }
        }
        if (knowledge != null) {
            knowledgeChecks(content, knowledge, out);
        }
        return out;
    }

    /**
     * Ids against the server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 9.4): an unknown item, mob or map is an ERROR; an item
     * players cannot get in the open, a mob they never meet and gear its holders never wear are WARNs.
     */
    private static void knowledgeChecks(LivingContent content, KnowledgeLint knowledge, List<String> out) {
        Map<String, List<KnowledgeLint.Holder>> holders = new LinkedHashMap<>();
        for (Persona persona : content.personas().values()) {
            String where = persona.file() + " persona " + persona.id();
            for (int itemId : persona.giftItemIds()) {
                knowledge.require(EntityKind.ITEM, itemId, where, out);
            }
            for (String known : content.knows(persona.id())) {
                knowledge.personaKnows(known, where, out);
            }
            if (persona.loadout() != null) {
                holders.computeIfAbsent(persona.loadout(), id -> new ArrayList<>())
                        .add(new KnowledgeLint.Holder("persona " + persona.id(), persona.level().min(), persona.level().max()));
            }
        }
        for (Quest quest : content.quests().values()) {
            String where = quest.file() + " quest " + quest.id();
            Set<Integer> dropped = new HashSet<>(); // the living world drops these itself (QuestLedger dropFrom)
            for (Step step : quest.steps()) {
                if (step.dropFrom() != null) {
                    dropped.add(step.itemId());
                }
            }
            Set<Integer> sourced = new HashSet<>();
            for (Step step : quest.steps()) {
                String stepWhere = where + " step " + step.id();
                List<Integer> items = new ArrayList<>();
                if (step.type() == StepType.COLLECT_ITEM || step.type() == StepType.GIVE_ITEM) {
                    items.add(step.itemId());
                }
                if (step.take() != null) {
                    items.add(step.take().itemId());
                }
                for (int itemId : items) {
                    if (knowledge.require(EntityKind.ITEM, itemId, stepWhere, out) && !dropped.contains(itemId) && sourced.add(itemId)) {
                        knowledge.obtainable(itemId, stepWhere, out);
                    }
                }
                Set<Integer> mobs = new LinkedHashSet<>(step.mobIds());
                if (step.dropFrom() != null) {
                    mobs.addAll(step.dropFrom().mobIds());
                }
                for (int mobId : mobs) {
                    if (knowledge.require(EntityKind.MOB, mobId, stepWhere, out)) {
                        knowledge.spawns(mobId, stepWhere, out);
                    }
                }
                for (int mapId : step.mapIds()) {
                    knowledge.require(EntityKind.MAP, mapId, stepWhere, out);
                }
                if (step.type() == StepType.DELIVER && step.to().startsWith("npc:")) {
                    try {
                        knowledge.require(EntityKind.NPC, Integer.parseInt(step.to().substring(4)), stepWhere, out);
                    } catch (NumberFormatException malformed) {
                        out.add("ERROR " + stepWhere + ": deliver target '" + step.to() + "' is not an NPC id");
                    }
                }
            }
            for (Content.ItemStack reward : quest.rewards().items()) {
                knowledge.require(EntityKind.ITEM, reward.itemId(), where + " rewards", out);
            }
            for (Content.GearReward gear : quest.rewards().gear()) {
                if (gear.owned()) {
                    Persona giver = content.persona(quest.persona());
                    knowledge.giversGear(where + " rewards gear", gear.pool().get(0), giver == null ? null : giver.id(),
                            giver == null ? -1 : jobMask(giver.job()), out);
                } else {
                    knowledge.friendGear(where + " rewards gear", gear.pool(), out);
                }
            }
        }
        for (Contract contract : content.contracts().values()) {
            String where = contract.file() + " contract " + contract.id();
            Set<Integer> priced = new HashSet<>();
            for (Content.ContractItem item : contract.price().items()) {
                if (knowledge.require(EntityKind.ITEM, item.itemId(), where, out) && priced.add(item.itemId())) {
                    knowledge.obtainable(item.itemId(), where, out);
                }
            }
            if (contract.price().proof() != null) {
                for (int mobId : contract.price().proof().mobIds()) {
                    if (knowledge.require(EntityKind.MOB, mobId, where, out)) {
                        knowledge.spawns(mobId, where, out);
                    }
                }
            }
        }
        for (Arc arc : content.arcs().values()) {
            Persona owner = authored(content, arc.persona());
            for (Chapter chapter : arc.chapters()) {
                if (owner != null && chapter.loadout() != null) {
                    holders.computeIfAbsent(chapter.loadout(), id -> new ArrayList<>()).add(new KnowledgeLint.Holder(
                            "arc " + arc.id() + " chapter " + chapter.id(), owner.level().min(), owner.level().max()));
                }
            }
        }
        for (Content.Loadout loadout : content.loadouts().values()) {
            String where = "loadouts.yaml loadout " + loadout.id();
            for (int itemId : loadout.equipIds()) {
                knowledge.require(EntityKind.ITEM, itemId, where, out);
            }
            for (Content.ItemStack consumable : loadout.consumables()) {
                knowledge.require(EntityKind.ITEM, consumable.itemId(), where, out);
            }
            for (List<Integer> pool : loadout.outfit().values()) {
                for (int itemId : pool) {
                    if (itemId != 0) {
                        knowledge.require(EntityKind.ITEM, itemId, where, out);
                    }
                }
            }
            knowledge.gear(where, loadout.equipIds(), loadout.outfit(), holders.getOrDefault(loadout.id(), List.of()), out);
        }
    }

    /**
     * An authored persona by id, never a hired citizen's: content may not name a {@code hire:} id, so a reference to one
     * is not found, and the lint never reaches the installed hire resolver.
     */
    private static Persona authored(LivingContent content, String id) {
        return id == null || LivingContent.isHire(id) ? null : content.personas().get(id);
    }

    private static Map<String, AgentTownLifeProfile> profilesById() {
        Map<String, AgentTownLifeProfile> profiles = new LinkedHashMap<>();
        for (AgentTownLifeProfile profile : AgentTownLifeProfileRepository.defaultRepository().profiles()) {
            profiles.put(profile.profileId(), profile);
        }
        return java.util.Collections.unmodifiableMap(profiles);
    }

    /**
     * Why {@code spot} (ResidentSpots' grammar: {@code venue:<id>}, {@code hotspot:<id>}, {@code rest:<n>},
     * {@code npc:<npcId>}, {@code x,y}) names nothing in {@code town}'s TownLife profile, or null when it resolves, when
     * there is no spot, or when the town itself is unknown (reported on its own). A spot that does not resolve puts the
     * Resident at the profile's fallback spot without a word, so a renamed venue is caught here.
     */
    static String unresolvedSpot(String town, String spot) {
        AgentTownLifeProfile profile = PROFILES.get(town);
        if (spot == null || spot.isBlank() || profile == null) {
            return null;
        }
        String[] parts = spot.split(":", 2);
        if (parts.length == 1) {
            return spot.matches("\\s*-?\\d+\\s*,\\s*-?\\d+\\s*") ? null : "spot '" + spot + "' is neither x,y nor kind:id";
        }
        String id = parts[1].trim();
        boolean found = switch (parts[0].trim()) {
            case "venue" -> profile.venues().stream().anyMatch(venue -> venue.id().equals(id) && !venue.spots().isEmpty());
            case "hotspot" -> profile.hotspots().stream().anyMatch(hotspot -> hotspot.id().equals(id)
                    && unresolvedSpot(town, "venue:" + hotspot.venueId()) == null);
            case "rest" -> id.matches("-?\\d+") && !profile.restSpots().isEmpty();
            case "npc" -> id.matches("\\d+");
            default -> false;
        };
        return found ? null : "spot '" + spot + "' is not in the " + town + " TownLife profile";
    }

    /** The reqJob bit of a persona's job branch (FriendForge.jobMask), -1 for a job name the game does not have. */
    private static int jobMask(String job) {
        try {
            return server.living.quest.FriendForge.jobMask(client.Job.valueOf(job).getId());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return -1;
        }
    }

    /** Outfit slot -> item id prefixes (id / 10000) that belong in it; 0 in a pool means nothing. */
    private static final Map<String, List<Integer>> OUTFIT_SLOTS = new LinkedHashMap<>();

    static {
        OUTFIT_SLOTS.put("hat", List.of(100));
        OUTFIT_SLOTS.put("top", List.of(104, 105));
        OUTFIT_SLOTS.put("bottom", List.of(106));
        OUTFIT_SLOTS.put("shoes", List.of(107));
        OUTFIT_SLOTS.put("gloves", List.of(108));
        OUTFIT_SLOTS.put("cape", List.of(110));
        OUTFIT_SLOTS.put("weapon", java.util.stream.IntStream.rangeClosed(130, 149).boxed().toList()); // not cash weapons (170)
    }

    /** Client quest ids are shorts; stock v83 stops below 30000, so the living world owns 30000-32767. */
    public static final int QUEST_LOG_ID_MIN = 30000;
    public static final int QUEST_LOG_ID_MAX = 32767;
}
