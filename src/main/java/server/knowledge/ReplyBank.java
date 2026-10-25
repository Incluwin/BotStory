package server.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Deterministic reply templates in a 2008 player voice (docs/SERVER_KNOWLEDGE_PLAN.md 5.4). Each key has a
 * default bank; the quiet and proper voices may add their own ("drop.one/quiet"). Variation comes from
 * independent axes, all drawn from one seed per (bot, asker, subject, turn): the template, an opener that
 * echoes the asker's own word ("steelies? ..."), a hedge, and the voice's own wording. The host's voice pack
 * (lowercasing, abbreviations, typos, emoticons) and typing delay are applied afterwards.
 */
final class ReplyBank {
    /** The four dialogue style ids in voices.yaml (citizens and companions share them). */
    enum Voice {
        CASUAL, QUIET, PLAYFUL, PROPER;

        static Voice of(String style) {
            if (style == null) {
                return CASUAL;
            }
            if (style.startsWith("quiet")) {
                return QUIET;
            }
            if (style.startsWith("playful")) {
                return PLAYFUL;
            }
            return style.startsWith("curious") || style.startsWith("proper") ? PROPER : CASUAL;
        }
    }

    private static final double ECHO_CHANCE = 0.25;
    private static final double FILLER_CHANCE = 0.08;
    private static final Map<String, List<String>> LINES = new HashMap<>();
    /** The proper voice spells out what the others abbreviate ("youre" is how templates write you're; see {@link #spell}). */
    private static final Map<String, String> PROPER_WORDS = Map.ofEntries(Map.entry("u", "you"), Map.entry("ur", "your"),
            Map.entry("youre", "you're"),
            Map.entry("r", "are"), Map.entry("em", "them"), Map.entry("n", "and"), Map.entry("thats", "that's"),
            Map.entry("dont", "don't"), Map.entry("cant", "can't"), Map.entry("idk", "I don't know"), Map.entry("lvl", "level"),
            Map.entry("rn", "right now"), Map.entry("prob", "probably"), Map.entry("tbh", "honestly"), Map.entry("ez", "easy"),
            Map.entry("havent", "haven't"), Map.entry("its", "it's"), Map.entry("im", "I'm"), Map.entry("i", "I"),
            Map.entry("ppl", "people"), Map.entry("thru", "through"), Map.entry("alot", "a lot"), Map.entry("pt", "party"),
            Map.entry("sry", "sorry"), Map.entry("afaik", "as far as I know"), Map.entry("yea", "yeah"));

    static {
        // no verb: the templates around {bucket} already say "drop" ("pigs drop em, pretty common")
        put("bucket.common", "pretty common", "ez to get", "u'll get some quick");
        put("bucket.sometimes", "not that rare", "takes a bit");
        put("bucket.rare", "rare tho", "takes forever");
        put("bucket.superrare", "super rare", "gl lol", "almost never");
        put("bucket.common/proper", "they're common", "easy to get");
        put("bucket.sometimes/proper", "not that rare", "it takes a while");
        put("bucket.rare/proper", "it's rare though", "it takes forever");
        put("bucket.superrare/proper", "it's super rare", "good luck though");
        put("bucket.common.boss", "takes a few runs", "u'll get it in a few runs");
        put("bucket.sometimes.boss", "takes alot of runs", "takes a bunch of runs");
        put("bucket.rare.boss", "if youre lucky lol", "takes forever");
        put("bucket.superrare.boss", "super rare", "gl lol");
        put("bucket.common.boss/proper", "it takes a few runs");
        put("bucket.sometimes.boss/proper", "it takes a lot of runs");
        put("bucket.rare.boss/proper", "only if you're lucky");
        put("bucket.superrare.boss/proper", "it's super rare");
        put("hedge", "prob ", "i think ", "pretty sure ");
        put("filler", "hmm ", "uh ");
        // personal knowledge (plan 7.3): a veteran's exact chance, and what a bot only heard
        put("bucket.exact", "like {pct}", "{pct} or so", "about {pct}");
        put("bucket.exact/proper", "about {pct}");
        put("bucket.exact.boss", "like {pct} a kill", "{pct} a run");
        put("bucket.exact.boss/proper", "about {pct} a kill");
        put("hedge.heard", "heard ", "someone said ", "apparently ");
        put("hedge.heard/proper", "I heard ");

        put("drop.one", "{mobs} {drop} em, {bucket}", "{mobs} in {map}, {bucket}", "{hedge}{mobs}, {bucket}",
                "{mobs} {drop} those, try {map}", "{mobs} at {map}, {bucket}");
        put("drop.one/quiet", "{mobs}", "{mobs}, {map}", "{mobs} at {map}");
        put("drop.one/proper", "{mobs} {drop} them, but {bucket}", "i think {mobs} {drop} those, around {map}",
                "{mobs} at {map} {drop} them");
        put("drop.one.common", "farm {mobs} at {map}", "{mobs} {drop} em alot, {map}");
        put("drop.two", "{mobs} or {mobs2}, {bucket}", "{mobs} n {mobs2} drop em", "{hedge}{mobs} or {mobs2}");
        put("drop.two/quiet", "{mobs} or {mobs2}");
        put("drop.two.rare", "{mobs} or {mobs2}, {bucket}", "{hedge}{mobs} or {mobs2}, {bucket}");
        put("drop.also.global", ", or anything really", ", anything can drop em too");
        put("drop.quest", "{mobs} but only when u have the quest", "only drops when u have the quest, {mobs}");
        put("drop.script", "only the {mobs} in {map}, talk to {npc}", "{mobs} in {map}, {npc} spawns em");
        put("drop.script/quiet", "{mobs}, {map}. talk to {npc}");
        put("drop.global", "anything drops em, {bucket}", "any mob can drop em, {bucket}");
        put("drop.global/quiet", "any mob, {bucket}");
        put("drop.pitch", "anything drops it, pqs give more", "any mob, but pqs n bosses give more");
        put("drop.none", "dont think anything drops those", "nothing drops em afaik");
        put("drop.none.shop", "nothing drops em, {npc} in {town} sells em", "{npc} in {town} sells em, nothing drops em");
        put("drop.none.fm", "nothing drops em, check fm", "cant get em around here, try fm");
        put("drop.unknown", "no idea what drops those", "never seen em drop, idk", "idk, never farmed those");
        put("drop.unknown/proper", "I don't know what drops those");
        put("cash", "thats cash shop stuff", "cash shop only");

        put("mob.drops", "{etc} mostly, sometimes {notable}", "{notable} if youre lucky lol", "mostly {etc}, {notable} sometimes");
        put("mob.drops/quiet", "{etc}, {notable}");
        put("mob.drops.etc", "{etc} n random stuff", "{etc} mostly");
        put("mob.drops.none", "nothing good tbh", "just junk lol");
        put("mob.drops.unknown", "never killed those, idk", "no idea, never hunted em");
        put("mob.drops.unknown/proper", "I have never hunted those");

        put("buy.here", "{role} right here", "{npc} sells em, {spot}", "{npc} sells em, right here");
        put("buy.here/quiet", "{spot}", "{npc}, {spot}", "{npc}, right here");
        put("buy.here/proper", "{npc} sells them, in the {spot}", "the {role} right here has them", "{npc} sells them, right here");
        put("buy.there", "{role} in {town}", "the {role} in {town} has em", "{npc} in {town} sells em");
        put("buy.there/quiet", "{town}, {spot}", "{npc}, {town}", "{town}");
        put("buy.there/proper", "the {role} in {town} has them", "{npc} in {town} sells them");
        put("buy.pitch", "{npc} in {town} trades em for perfect pitch", "trade perfect pitch with {npc} in {town}");
        put("buy.none.drop", "npcs dont sell those, {mobs} {drop} em", "cant buy em, {mobs} {drop} em");
        put("buy.none", "cant buy em, check fm", "npcs dont sell those, try fm");
        put("get.fm", "check fm prob", "ppl sell em in fm");
        put("buy.unknown", "idk who sells em", "not sure where to buy those");
        put("buy.unknown/proper", "I'm not sure who sells those");

        put("where.npc.here", "right here lol", "look around lol, right here");
        put("where.npc.town", "{npc}s in the {spot}", "{spot}, right here in {town}");
        put("where.npc.square", "{npc}s right here in {town}", "{npc}? right here in {town}");
        put("where.npc.far", "{npc}s in {town}, in the {spot}", "{town}, {spot}", "{npc}? in {town}, {spot}");
        put("where.npc.far/quiet", "{town}, {spot}");
        put("where.npc.far/proper", "{npc}'s in {town}, in the {spot}");
        put("where.npc.far.square", "{npc}s in {town}", "{npc}? {town}");
        put("where.map", "{map} is near {town}", "{map}? near {town}");
        put("where.map/quiet", "near {town}");
        put("where.map.close", "{map} is near {town}", "{map}? near {town}", "near {town}, its close");
        put("where.map.close/quiet", "near {town}");
        put("where.map.here", "youre on it lol", "youre here lol");
        put("where.mob", "{mobs} {r} at {map}", "{map} has tons of {mobs}", "{mobs}? around {town}");
        put("where.mob/quiet", "{map}");
        put("where.mob/proper", "{mobs} {r} at {map}", "there are lots of {mobs} at {map}");
        put("where.mob.boss", "{mobs} {r} at {map}", "{mobs}? around {town}", "{map}, near {town}");
        put("where.mob.boss/quiet", "{map}");
        put("where.mob.script", "only in {map}, talk to {npc}");
        put("where.mob.none", "dont think they spawn anywhere normal");
        put("where.pq", "{pq}s in {town}, talk to {npc}", "{town}, {npc} starts it");
        put("where.pq/quiet", "{town}, {npc}");
        // CWKPQ plan C2: the keep's gate takes the Crimsonwood Keystone, so its PQ is never told without it
        put("where.pq.keystone", "{pq}s in crimsonwood keep, talk to {npc}. u need the keystone from lukan first",
                "{npc} in crimsonwood keep starts it, get the keystone from lukan first");
        put("where.pq.keystone/quiet", "crimsonwood keep, {npc}. keystone from lukan first");
        put("get.keystone", "lukan in the phantom forest gives it, do his quests",
                "do lukans quests, hes on phantom road in the phantom forest", "lukan gives it, phantom forest. lv 70 quests");
        put("get.keystone/quiet", "lukan, phantom forest");
        put("where.role.here", "{role}s right here, {spot}", "{spot}, {npc}");
        put("where.role.here/quiet", "{spot}");
        put("where.role.square", "{role}s right here, {npc}", "{npc}, right here");
        put("where.role.far", "nearest {role}s in {town}", "{town} has one, {spot}");
        put("where.role.far/quiet", "{town}");
        put("where.role.far.square", "nearest {role}s in {town}", "{town} has one, {npc}");
        put("where.am.i", "youre in {map} lol", "{map}", "{map}, near {town}");

        put("route.same", "youre already in {town} lol", "youre here lol");
        put("route.near", "its right here in {town}", "{dest}s right here in {town}");
        put("route.walk.via", "its a walk, go thru {via}", "just walk, its past {via}", "walk thru {via}");
        put("route.walk", "just walk, its close", "{dest}? right by {town}, just walk");
        put("route.walk.far", "its a walk from {from}", "long walk lol", "u gotta walk, its a bit from {from}");
        put("route.taxi", "just take the taxi", "taxi to {dest}, its ez", "take the cab lol");
        put("route.one", "{ride} from {a} to {b}", "take the {ride} at {a}", "{ride} to {b}, its at {a}");
        put("route.one/proper", "take the {ride} from {a} to {b}");
        put("route.one.then", "{ride} to {b}, then walk thru {via}");
        put("route.one.walk", "{ride} to {b}, then walk to {dest}", "take the {ride} to {b}, then walk to {dest}");
        put("route.two", "{ride} to {b}, then the {ride2} to {b2}", "{ride} to {b} then {ride2} to {b2}");
        put("route.two.then", "{ride} to {b}, {ride2} to {b2}, then thru {via}");
        put("route.two.walk", "{ride} to {b}, {ride2} to {b2}, then walk to {dest}");
        put("route.two.on", "{ride} to {b}, {ride2} to {b2}, then on to {dest}");
        put("route.far", "thats far for ur lvl lol, ");
        put("route.rock", ", or use ur tele rock if u saved it");
        put("route.none", "idk how to get there tbh");
        put("route.fm", "every town has a fm portal", "go in the fm portal in {town}");

        put("train", "{map} is good at ur lvl", "{mobs} at {map}", "try {map}, ok exp", "{map}, {mobs} there");
        put("train/quiet", "{map}", "{mobs}, {map}");
        put("train/proper", "{map} is good for your level", "try the {mobs} at {map}");
        put("train.other", "{map} at {lvl}", "{mobs} at {map}", "try {map}");
        put("train.kpq", "kpq till 30 lol", "just kpq till 30");
        put("train.high", "youre higher than me lol idk", "idk thats past me");
        put("train.high.other", "idk thats past me", "no clue, thats past me");
        put("train.none", "idk, ask someone ur lvl");
        put("train.unknown", "idk, i trained somewhere else", "not sure, didnt train around here");
        put("train.unknown/proper", "I'm not sure, I trained somewhere else");
        // its own spot across the sea from the asker: say where it is
        put("train.far", "{map} in {town}, i trained there", "i trained at {map} in {town}", "{map} in {town} was good for me");
        put("train.far/quiet", "{map}, {town}");
        put("train.far/proper", "I trained at {map} in {town}");

        put("build.none", "idk tbh, never built one", "no clue, ask one of them");
        put("build.none/proper", "I'm not sure, I've never built one");

        put("price.known", "like {p}", "{p}ish last i saw", "prob {p}, check fm");
        put("price.known/proper", "around {p} last i saw", "probably {p}, check the fm");
        put("price.set", "like {p} a set", "{p}ish a set");
        put("price.each", "like {p} each", "{p}ish each");
        put("price.unknown", "no idea what those go for", "havent seen any lately", "dunno, check the fm");
        put("price.shop", "like {p} at the store", "{npc} sells em for {p}", "{p} at {npc}");

        put("level.mob", "{mobs} {r} like {lvl5}", "{lvl5}ish", "like {lvl5}");
        put("level.mob/quiet", "{lvl5}ish", "lv {lvl5}");
        put("level.mob.near", "around ur lvl, like {lvl5}", "{mobs} {r} around ur lvl, {lvl5}ish");
        put("level.mob.high", "bit high for u rn, like {lvl5}", "{lvl5}ish, bit high for u");
        put("level.item", "lv {req}", "{req} to wear", "{item} is lv {req}");
        put("level.item.use", "lv {req}", "{req} to use", "u need lv {req}");
        put("level.item.none", "no lvl req i think");
        put("level.pq", "{min}-{max}", "{min} to {max}, need a pt");
        put("level.map", "mobs there r like {lvl5}", "like {lvl5}ish mobs");

        put("job.where", "{npc} in {town}, {spot}", "go see {npc} in {town}", "{npc}, {spot} in {town}");
        put("job.where.level", "at lv {req}, go see {npc} in {town}", "u need lv {req} first, then {npc} in {town}");
        put("job.ask", "what job u going?", "which job?");
        put("job.maxed", "youre maxed lol");
        put("job.other", "ask ur instructor in {town}");

        put("map.good", "yea its good for ur lvl", "decent exp here", "yea {mobs} {r} good for u");
        put("map.low", "kinda low for u, try {better}", "too low for u lol");
        put("map.high", "bit high for u rn", "careful, its high for u");
        put("map.town", "its a town lol");
        put("map.drops.none", "nothing spawns here lol");

        put("rules.rates", "exp is {x}x, {x30}x till 30, drops {d}x", "{x}x exp ({x30}x till 30), {d}x drops");
        put("rules.rates.exp", "exp is {x}x, {x30}x till 30", "{x}x exp, {x30}x till 30");
        put("rules.rates.meso", "meso is {m}x", "{m}x meso");
        put("rules.rates.drop", "drops r {d}x", "{d}x drops");
        put("rules.rates.boss", "boss drops r {b}x", "{b}x for bosses");
        put("rules.gear", "u can wear anything, just need the lvl n ur main stat", "any class can wear it, ignore the red text");
        put("rules.pets", "pets never expire here", "nah pets dont expire");
        put("rules.slots", "bags r maxed already", "ur inv is already max");
        put("rules.rock", "u get one at lv 10, its permanent", "everyone gets a tele rock at 10");
        put("rules.rates.vague", "exp is pretty fast here", "rates r good, exp goes quick", "drops r boosted, exp is fast");
        put("rules.rates.vague/proper", "The rates are good here, experience comes pretty quickly.");
        put("rules.rates.vague.exp", "exp is pretty fast here", "exp goes quick", "u level fast here");
        put("rules.rates.vague.exp/proper", "Experience comes pretty quickly here.");
        put("rules.rates.vague.meso", "mesos r decent here", "meso is boosted", "u make mesos pretty quick");
        put("rules.rates.vague.meso/proper", "Mesos come pretty easily here.");
        put("rules.rates.vague.drop", "drops r boosted", "stuff drops alot more here", "drops r good here");
        put("rules.rates.vague.drop/proper", "Monsters drop a lot more here.");
        put("rules.rates.vague.boss", "bosses drop alot more", "boss drops r boosted");
        put("rules.rates.vague.boss/proper", "Bosses drop a lot more here.");

        put("deflect", "never been that far tbh", "ask someone higher", "no clue, thats way past me lol", "idk, never been there",
                "way past my lvl", "havent gone that far yet");
        // a synthesized past's level claim about an item or a mob (a place keeps the words above)
        put("deflect.item", "those come from stuff way past my lvl", "ask someone higher", "no clue, thats way past me lol",
                "way past my lvl", "havent gone that far yet");
        put("deflect.mob", "never fought those, way past me", "ask someone higher", "no clue, thats way past me lol",
                "way past my lvl", "havent gone that far yet");
        put("rough", "{town} somewhere, high lvl tho", "somewhere around {town} i think, way past my lvl",
                "{x}? {town} somewhere, too high for me lol");
        put("rough/quiet", "{town} somewhere. high lvl", "around {town}");
        put("rough/proper", "somewhere around {town}, i think, but its way past my level", "{town}, i believe. its far above my level though");
        put("rough.town", "{x}? never been, way too high for me", "havent been to {x}, its high lvl", "{x}? too high for me lol");
        put("rough.town/quiet", "never been. high lvl");
        put("rough.town/proper", "i have never been to {x}, its far above my level");
        // a place it never went to, past nothing: no level claim
        put("deflect.unseen", "no clue tbh", "never been, idk", "havent been there", "idk, never seen it");
        put("deflect.unseen/quiet", "no idea");
        put("deflect.unseen/proper", "i don't know, i haven't been there", "i couldn't tell you");
        put("deflect.unseen.item", "never seen those drop", "no idea, never had one", "idk, never seen em");
        put("deflect.unseen.item/quiet", "no idea");
        put("deflect.unseen.item/proper", "i don't know, i have never seen one", "i couldn't tell you");
        put("deflect.unseen.mob", "never fought those", "no idea, never hunted em", "idk, never seen em");
        put("deflect.unseen.mob/quiet", "no idea");
        put("deflect.unseen.mob/proper", "i don't know, i have never fought those", "i couldn't tell you");
        put("rough.heard", "{town} somewhere i think, never been", "around {town} i heard");
        put("rough.heard/quiet", "around {town}");
        put("rough.heard/proper", "somewhere around {town}, i think. i haven't been there");
        put("rough.town.heard", "{x}? never been there myself", "havent been to {x} yet");
        put("rough.town.heard/proper", "i haven't been to {x} myself");
        put("boundary", "no clue tbh", "never heard of those", "idk, ask in {askin}", "idk sry");
        put("clarify", "{x}?", "which one, {x}?");
        put("clarify.level", "what lvl?", "what lvl r they");
        put("guess", "prob {x}, ");
        put("thanks", "np", "np gl", "yw");
        put("repeat", "like i said, {short}");
        put("budget", "idk man ask someone else", "ask someone else lol");
    }

    private ReplyBank() {
    }

    /** Every key with its own templates ("drop.one", "drop.one/quiet"), read-only: the wording lint test reads them. */
    static Map<String, List<String>> all() {
        return java.util.Collections.unmodifiableMap(LINES);
    }

    /**
     * One reply for {@code key}: a template the voice may use whose slots are all filled, shuffled by
     * {@code rng}, the first that fits {@code maxChars} (else the shortest). The quiet voice takes the shorter of
     * two draws. Null when no template can be filled.
     */
    static String line(String key, Voice voice, SplittableRandom rng, int maxChars, Map<String, String> vars) {
        List<String> filled = new ArrayList<>();
        for (String template : templates(key, voice)) {
            String text = fill(template, vars);
            if (text != null) {
                filled.add(text);
            }
        }
        if (filled.isEmpty()) {
            return null;
        }
        String chosen = null;
        for (int i = filled.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            String swap = filled.get(i);
            filled.set(i, filled.get(j));
            filled.set(j, swap);
        }
        for (String text : filled) {
            if (text.length() <= maxChars) {
                chosen = text;
                break;
            }
        }
        if (voice == Voice.QUIET && filled.size() > 1 && chosen != null) {
            String other = filled.get(filled.size() - 1);
            chosen = other.length() < chosen.length() ? other : chosen;
        }
        if (chosen == null) {
            chosen = filled.stream().min((a, b) -> Integer.compare(a.length(), b.length())).orElseThrow();
        }
        return chosen;
    }

    /** A bare phrase (bucket, hedge, prefix) for the voice; "" when the key has none. */
    static String phrase(String key, Voice voice, SplittableRandom rng) {
        List<String> options = templates(key, voice);
        return options.isEmpty() ? "" : options.get(rng.nextInt(options.size()));
    }

    /**
     * The final touch on a chosen reply: sometimes an opener echoing the asker's word ("steelies? ...") or a
     * filler, then the proper voice's spelled-out words and capital. Never longer than {@code maxChars}.
     */
    static String finish(String reply, Voice voice, SplittableRandom rng, int maxChars, String echo) {
        String out = reply;
        double roll = rng.nextDouble();
        if (echo != null && !echo.isEmpty() && voice != Voice.QUIET && roll < ECHO_CHANCE
                && !out.startsWith(echo) && out.length() + echo.length() + 2 <= maxChars) {
            out = echo + "? " + out;
        } else if ((voice == Voice.CASUAL || voice == Voice.PLAYFUL) && roll > 1 - FILLER_CHANCE
                && out.length() + 4 <= maxChars) {
            out = phrase("filler", voice, rng) + out;
        }
        return spell(out, voice, maxChars);
    }

    /**
     * The voice's own wording of a whole reply: "youre" (the templates' you're) is "ur" in chat voices; the proper
     * voice spells words out and starts with a capital, once, on the combined text (never after a prefix).
     */
    static String spell(String reply, Voice voice, int maxChars) {
        String out = reply;
        if (voice != Voice.PROPER) {
            return (" " + out + " ").replace(" youre ", " ur ").trim();
        }
        StringBuilder spelled = new StringBuilder(out.length() + 16);
        for (String word : out.split(" ")) {
            String bare = word.replaceAll("[,?.!)(]+$", "");
            String tail = word.substring(bare.length());
            String proper = PROPER_WORDS.get(bare);
            spelled.append(spelled.length() == 0 ? "" : " ").append(proper == null ? word : proper + tail);
        }
        String text = spelled.toString().replace(" lol", "").trim();
        if (text.length() <= maxChars) {
            out = text;
        }
        return out.isEmpty() ? out : Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /** A finished reply put after a prefix ("prob athena, " + "Athena's in Henesys"): no capital mid-line. */
    static String uncapitalize(String reply) {
        return reply == null || reply.isEmpty() || reply.length() > 1 && Character.isUpperCase(reply.charAt(1))
                ? reply : Character.toLowerCase(reply.charAt(0)) + reply.substring(1);
    }

    private static List<String> templates(String key, Voice voice) {
        List<String> own = voice == Voice.CASUAL ? null : LINES.get(key + "/" + voice.name().toLowerCase(java.util.Locale.ROOT));
        List<String> base = LINES.getOrDefault(key, List.of());
        if (own == null) {
            return base;
        }
        if (voice == Voice.PLAYFUL) {
            List<String> both = new ArrayList<>(base);
            both.addAll(own);
            return both;
        }
        return own;
    }

    /** The template with every {slot} replaced; null when a slot it uses has no value. */
    static String fill(String template, Map<String, String> vars) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            out.append(template, i, open);
            String value = vars.get(template.substring(open + 1, close));
            if (value == null || value.isEmpty() && !template.substring(open + 1, close).equals("hedge")) {
                return null;
            }
            out.append(value);
            i = close + 1;
            boolean contraction = i < template.length() && template.charAt(i) == 's'
                    && (i + 1 == template.length() || !Character.isLetter(template.charAt(i + 1)));
            if (contraction && !value.isEmpty() && "sxz".indexOf(value.charAt(value.length() - 1)) >= 0) {
                out.append(" is"); // "{npc}s in kerning": "lakelis is in kerning", never "lakeliss"
                i++;
            }
        }
        return out.toString().trim();
    }

    private static void put(String key, String... lines) {
        LINES.put(key, List.of(lines));
    }
}
