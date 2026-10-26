package server.itcg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MapleStory iTCG forging recipes (docs/ITCG_MASTERIA_PLAN.md section 2): who draws which manual from which
 * artifacts, what Spindle forges from a manual or a Materia Orb, the Bosshunter set, Corine's spirit upgrades,
 * Stirgeman's ladders, T-1337's juices, Ridley's Stone, Adonis's Set 5 and the two exchanges BotStory adds (Lita
 * Lawless's bounty board and Cryssea's card swap). The GMS numbers are Hidden Street's and MapleSecrets'; there are no
 * meso fees because neither guide lists one. Data only: {@link ItcgForge} runs them.
 */
public final class ItcgRecipes {
    /** {@code count} of {@code itemId}. */
    public record Stack(int itemId, int count) {
    }

    /**
     * Inputs to one result. {@code results} holds one id for a fixed recipe, several for a draw ({@link ItcgForge}
     * picks one at random); {@code petLevel} is the summoned pet level the recipe asks for (Corine), 0 for none.
     */
    public record Recipe(String id, List<Integer> results, int resultCount, List<Stack> inputs, int exp, int petLevel) {
        public Recipe {
            results = List.copyOf(results);
            inputs = List.copyOf(inputs);
        }

        /** The one result of a fixed recipe, the first of a draw. */
        public int result() {
            return results.get(0);
        }

        public boolean draw() {
            return results.size() > 1;
        }
    }

    private static Stack s(int itemId, int count) {
        return new Stack(itemId, count);
    }

    private static Recipe fixed(String id, int result, Stack... inputs) {
        return new Recipe(id, List.of(result), 1, List.of(inputs), 0, 0);
    }

    // ---- Set 1 artifacts (Commodity SN 80000008-80000018) --------------------------------------------------------
    public static final int DARK_MATTER = 4_031_750;
    public static final int VORTICULAR_GYRO = 4_031_751;
    public static final int BLINKING_DINGBAT = 4_031_752;
    public static final int ZETA_RESIDUE = 4_031_753;
    public static final int BLACK_VERSAL_MATERIA = 4_031_754;
    public static final int TARU_TOTEM = 4_031_755;
    public static final int MYSTIC_ASTROLABE = 4_031_756;
    public static final int ANTELLION_RELIC = 4_031_757;
    public static final int NARICAIN_JEWEL = 4_031_758;
    public static final int SUBANI_ANKH = 4_031_759;
    public static final int WHITE_VERSAL_MATERIA = 4_031_760;
    public static final int MATERIA_ORB_ID = 4_031_761;

    // ---- Set 2 pieces (Commodity SN 80000019-80000030) -----------------------------------------------------------
    public static final int ANCIENT_HELM = 4_031_897;
    public static final int ANCIENT_ARMOR = 4_031_898;
    public static final int ANCIENT_GREAVES = 4_031_899;
    public static final int RIDLEYS_BOOK_OF_RITUALS = 4_031_900;
    public static final int STONE_TIGER_HEAD = 4_031_913;
    public static final int TYPHON_CREST = 4_031_914;
    public static final int LEFAY_JEWEL = 4_031_915;
    public static final int PHAROAHS_WRAPPINGS = 4_031_916;
    public static final int CRYSTAL_SHARD = 4_031_917;
    public static final int ANCIENT_FACEGUARD = 4_031_918;
    public static final int ANCIENT_GI = 4_031_919;
    public static final int ANCIENT_BOOTS = 4_031_920;

    // ---- boss trophies -------------------------------------------------------------------------------------------
    public static final int PAPULATUS_CURL = 4_031_901;
    public static final int TENGU_NOSE = 4_031_902;
    public static final int JACK_O_LANTERN = 4_031_903;
    public static final int ERGOTHS_JAWBONE = 4_031_904;
    public static final int PIANUS_SCALE = 4_031_905;
    public static final int BALROG_CLAW = 4_031_906;
    public static final int BIGFOOTS_TOE = 4_032_013;
    public static final int ZAKUM_DIAMOND = 4_032_133;

    // ---- Sets 3-5 ------------------------------------------------------------------------------------------------
    public static final int TARU_SPIRIT_FEATHER = 4_031_936;
    public static final int JUNGLE_LILY = 4_031_937;
    public static final int TRIFERIUM_FUEL_CELL = 4_032_021;
    public static final int DIFERIUM_FUEL_CELL = 4_032_022;
    public static final int RIDLEYS_STONE = 4_032_026;
    public static final int STONE_DENARI = 4_032_134;

    // ---- common materials ----------------------------------------------------------------------------------------
    static final int SCREW = 4_003_000;
    static final int POWER_CRYSTAL_ORE = 4_004_000;
    static final int DARK_CRYSTAL_ORE = 4_004_004;
    static final int POWER_CRYSTAL = 4_005_000;
    static final int WISDOM_CRYSTAL = 4_005_001;
    static final int DEX_CRYSTAL = 4_005_002;
    static final int LUK_CRYSTAL = 4_005_003;
    static final int DARK_CRYSTAL = 4_005_004;
    static final int GOLD_ORE = 4_010_006;
    static final int STEEL_PLATE = 4_011_001;
    static final int MITHRIL_PLATE = 4_011_002;
    static final int SILVER_PLATE = 4_011_004;
    static final int GOLD_PLATE = 4_011_006;
    static final int BLACK_CRYSTAL_ORE = 4_020_008;
    static final int OPAL = 4_021_004;
    static final int SAPPHIRE = 4_021_005;
    static final int DIAMOND = 4_021_007;
    static final int BLACK_CRYSTAL = 4_021_008;
    static final int LEATHER = 4_000_021;
    static final int DRAGON_SKIN = 4_000_030;
    static final int BOW_PRODUCTION_MANUAL = 4_131_010;
    static final int WORK_GLOVES = 1_082_002;
    static final int MEBA = 1_472_007;
    static final int ILBI_THROWING_STARS = 2_070_006;

    /** The Glimmer Man's synthesis (quest 4924's Act): the orb and 20,000 EXP. */
    public static final int GLIMMER_QUEST = 4924;
    public static final Recipe MATERIA_ORB = new Recipe("materia-orb", List.of(MATERIA_ORB_ID), 1,
            List.of(s(WHITE_VERSAL_MATERIA, 1), s(BLACK_CRYSTAL, 7)), 20_000, 0);

    /** Professor Foxwit's quest-log shell (4926 "Interstellar Relics") and John Barricade's (4927 "Ancient Artifacts"). */
    public static final int FOXWIT_QUEST = 4926;
    public static final int BARRICADE_QUEST = 4927;

    /** Set 1 manuals Professor Foxwit draws. */
    public static final List<Recipe> FOXWIT_MANUALS = List.of(
            fixed("sirius-cloak-manual", 4_031_827, s(BLINKING_DINGBAT, 1), s(DARK_MATTER, 1), s(BLACK_VERSAL_MATERIA, 1)),
            fixed("zeta-cape-manual", 4_031_826, s(DARK_MATTER, 1), s(VORTICULAR_GYRO, 1)),
            fixed("black-phoenix-shield-manual", 4_031_829, s(VORTICULAR_GYRO, 1), s(ZETA_RESIDUE, 1),
                    s(BLACK_VERSAL_MATERIA, 1)),
            fixed("dark-shards-manual", 4_031_828, s(ZETA_RESIDUE, 1), s(BLINKING_DINGBAT, 1)));

    /** Set 2 manuals John Barricade draws. */
    public static final List<Recipe> BARRICADE_MANUALS = List.of(
            fixed("crystal-leaf-earrings-manual", 4_031_825, s(TARU_TOTEM, 1), s(NARICAIN_JEWEL, 1), s(MYSTIC_ASTROLABE, 1)),
            fixed("antellion-miter-manual", 4_031_823, s(ANTELLION_RELIC, 1), s(MYSTIC_ASTROLABE, 1)),
            fixed("infinity-circlet-manual", 4_031_822, s(NARICAIN_JEWEL, 1), s(SUBANI_ANKH, 1)),
            fixed("stormcaster-gloves-manual", 4_031_824, s(ANTELLION_RELIC, 1), s(SUBANI_ANKH, 1), s(TARU_TOTEM, 1)),
            fixed("glitter-gloves-manual", 4_031_910, s(LEFAY_JEWEL, 1), s(PHAROAHS_WRAPPINGS, 1)),
            fixed("facestompers-manual", 4_031_911, s(TARU_TOTEM, 1), s(STONE_TIGER_HEAD, 1)),
            fixed("winkel-manual", 4_031_909, s(LEFAY_JEWEL, 1), s(TYPHON_CREST, 1)),
            fixed("neva-manual", 4_031_908, s(PHAROAHS_WRAPPINGS, 1), s(TYPHON_CREST, 1)),
            fixed("crystal-ilbi-manual", 4_031_912, s(CRYSTAL_SHARD, 1), s(NARICAIN_JEWEL, 1)),
            fixed("tigers-fang-manual", 4_031_907, s(STONE_TIGER_HEAD, 1), s(CRYSTAL_SHARD, 1)));

    /** What Spindle forges from a manual. */
    public static final List<Recipe> SPINDLE_FORGES = List.of(
            fixed("sirius-cloak", 1_102_145, s(4_031_827, 1), s(GOLD_PLATE, 5), s(SAPPHIRE, 5), s(DIAMOND, 2)),
            fixed("zeta-cape", 1_102_146, s(4_031_826, 1), s(POWER_CRYSTAL_ORE, 5), s(GOLD_ORE, 5), s(OPAL, 5)),
            fixed("black-phoenix-shield", 1_092_052, s(4_031_829, 1), s(MITHRIL_PLATE, 10), s(SAPPHIRE, 2),
                    s(DARK_CRYSTAL_ORE, 1)),
            fixed("dark-shards", 1_032_049, s(4_031_828, 1), s(BLACK_CRYSTAL_ORE, 10), s(POWER_CRYSTAL_ORE, 10)),
            fixed("crystal-leaf-earrings", 1_032_048, s(4_031_825, 1), s(DIAMOND, 2)),
            fixed("antellion-miter", 1_002_675, s(4_031_823, 1), s(GOLD_PLATE, 5), s(STEEL_PLATE, 5), s(SCREW, 20)),
            fixed("infinity-circlet", 1_002_676, s(4_031_822, 1), s(SILVER_PLATE, 5), s(POWER_CRYSTAL, 1),
                    s(WISDOM_CRYSTAL, 1)),
            fixed("stormcaster-gloves", 1_082_223, s(4_031_824, 1), s(LEATHER, 15), s(POWER_CRYSTAL, 2)),
            fixed("glitter-gloves", 1_082_230, s(4_031_910, 1), s(WORK_GLOVES, 1), s(DIAMOND, 6), s(WISDOM_CRYSTAL, 6)),
            fixed("facestompers", 1_072_344, s(4_031_911, 1), s(STEEL_PLATE, 50), s(LEATHER, 25), s(DRAGON_SKIN, 50),
                    s(SCREW, 25)),
            fixed("winkel", 1_452_053, s(4_031_909, 1), s(BOW_PRODUCTION_MANUAL, 1), s(SCREW, 50), s(DEX_CRYSTAL, 6),
                    s(BLACK_CRYSTAL, 6)),
            fixed("neva", 1_472_064, s(4_031_908, 1), s(MEBA, 1), s(BLACK_CRYSTAL, 6), s(LUK_CRYSTAL, 6)),
            fixed("crystal-ilbi", 2_070_016, s(4_031_912, 1), s(ILBI_THROWING_STARS, 1), s(LUK_CRYSTAL, 7),
                    s(DARK_CRYSTAL, 1)),
            fixed("tigers-fang", 1_402_045, s(4_031_907, 1), s(POWER_CRYSTAL, 6), s(BLACK_CRYSTAL, 6)));

    /** The eleven L70 Versal weapons Spindle makes from one Materia Orb. */
    public static final List<Integer> VERSAL_WEAPONS = List.of(1_302_079, 1_322_059, 1_412_032, 1_432_045, 1_442_060,
            1_382_053, 1_452_052, 1_462_046, 1_472_062, 1_332_064, 1_332_065);

    /** John Barricade's Bosshunter set: warriors, bowmen and pirates first, then magicians and thieves. */
    public static final List<Recipe> BOSSHUNTER = List.of(
            fixed("bosshunter-helm", 1_002_739, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_HELM, 1), s(ERGOTHS_JAWBONE, 1),
                    s(TENGU_NOSE, 1)),
            fixed("bosshunter-armor", 1_052_148, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_ARMOR, 1), s(PAPULATUS_CURL, 1),
                    s(PIANUS_SCALE, 1)),
            fixed("bosshunter-greaves", 1_072_342, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_GREAVES, 1),
                    s(BALROG_CLAW, 1), s(JACK_O_LANTERN, 1)),
            fixed("bosshunter-faceguard", 1_002_740, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_FACEGUARD, 1),
                    s(TENGU_NOSE, 1), s(JACK_O_LANTERN, 1)),
            fixed("bosshunter-gi", 1_052_149, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_GI, 1), s(PAPULATUS_CURL, 1),
                    s(BALROG_CLAW, 1)),
            fixed("bosshunter-boots", 1_072_343, s(RIDLEYS_BOOK_OF_RITUALS, 1), s(ANCIENT_BOOTS, 1), s(ERGOTHS_JAWBONE, 1),
                    s(PIANUS_SCALE, 1)));

    /** Corine's spirit upgrades: L10 -> -suma (pet 15+), -suma -> -magna (pet 30+). */
    public static final List<Recipe> CORINE = corine(new int[][] {
            {1_452_054, 1_452_055, 1_452_056}, // Akha (bow)
            {1_462_047, 1_462_048, 1_462_049}, // Xaru (crossbow)
            {1_472_065, 1_472_066, 1_472_067}, // Kuma (claw)
            {1_332_067, 1_332_068, 1_332_069}, // Maku (LUK dagger)
            {1_332_070, 1_332_071, 1_332_072}, // Maku (STR dagger)
            {1_382_054, 1_382_055, 1_382_056}}); // Umaru (staff)

    private static List<Recipe> corine(int[][] lines) {
        ArrayList<Recipe> out = new ArrayList<>();
        for (int[] line : lines) {
            out.add(new Recipe("suma-" + line[1], List.of(line[1]), 1, List.of(s(line[0], 1), s(TARU_SPIRIT_FEATHER, 1),
                    s(WISDOM_CRYSTAL, 5), s(JUNGLE_LILY, 10)), 0, 15));
        }
        for (int[] line : lines) {
            out.add(new Recipe("magna-" + line[2], List.of(line[2]), 1, List.of(s(line[1], 1), s(TARU_SPIRIT_FEATHER, 2),
                    s(WISDOM_CRYSTAL, 15), s(JUNGLE_LILY, 50)), 0, 30));
        }
        return List.copyOf(out);
    }

    // ---- Stirgeman (Set 4) ---------------------------------------------------------------------------------------
    static final int STRETCHY_MATERIAL = 4_032_030;
    static final int DUCT_TAPE = 4_032_029;
    static final int COAT_HANGER = 4_032_027;
    static final int WAD_OF_GUM = 4_032_028;
    static final int LOADED_SPRING = 4_000_399;

    /** One Stirgeman line: what an attempt costs and, for every rung, the next rung(s) (two where the line forks). */
    public record StirgeLine(String id, List<Stack> cost, Map<Integer, List<Integer>> next) {
    }

    private static Map<Integer, List<Integer>> ladder(Object... rungs) {
        Map<Integer, List<Integer>> next = new LinkedHashMap<>();
        for (int i = 0; i + 1 < rungs.length; i++) {
            List<Integer> from = rungs[i] instanceof List<?> fork ? fork.stream().map(Integer.class::cast).toList()
                    : List.of((Integer) rungs[i]);
            List<Integer> to = rungs[i + 1] instanceof List<?> fork ? fork.stream().map(Integer.class::cast).toList()
                    : List.of((Integer) rungs[i + 1]);
            for (int item : from) {
                next.put(item, to);
            }
        }
        return next;
    }

    public static final List<StirgeLine> STIRGEMAN = List.of(
            new StirgeLine("cape", List.of(s(STRETCHY_MATERIAL, 12), s(DUCT_TAPE, 5), s(COAT_HANGER, 8)),
                    ladder(1_102_176, 1_102_177, 1_102_178, 1_102_179, 1_102_180, List.of(1_102_181, 1_102_182),
                            1_102_183)),
            new StirgeLine("pants", List.of(s(STRETCHY_MATERIAL, 8), s(WAD_OF_GUM, 8), s(LOADED_SPRING, 15), s(SCREW, 20)),
                    ladder(1_060_127, 1_060_128, 1_060_129, 1_060_130, 1_060_131, 1_060_132, 1_060_133)),
            new StirgeLine("skirt", List.of(s(STRETCHY_MATERIAL, 8), s(WAD_OF_GUM, 8), s(LOADED_SPRING, 15), s(SCREW, 20)),
                    ladder(1_061_149, 1_061_150, 1_061_151, 1_061_152, 1_061_153, 1_061_154, 1_061_155)),
            new StirgeLine("whip", List.of(s(COAT_HANGER, 15), s(LOADED_SPRING, 10), s(DUCT_TAPE, 5), s(WAD_OF_GUM, 8)),
                    ladder(1_302_088, 1_302_089, 1_302_090, 1_302_091, List.of(1_302_092, 1_302_093), 1_302_094)));

    // ---- T-1337 (Set 4) ------------------------------------------------------------------------------------------
    static final int BOOMER_CORE = 4_000_391;
    static final int SPARKPLUG = 4_032_023;
    static final int JUMPER_CABLE = 4_032_024;
    static final int T1_SOCKET_ADAPTER = 4_032_025;
    /** VitroJuice, NitroJuice, ElectroJuice, MegaJuice, GigaJuice, JigaJuice. */
    public static final List<Integer> JUICES = List.of(2_022_338, 2_022_339, 2_022_341, 2_022_342, 2_022_343, 2_022_344);
    /** A batch of one random juice: 10 on a Diferium cell, half as many again (15) on a Triferium one; 2,500 EXP. */
    public static final List<Recipe> T1337 = List.of(
            new Recipe("juice-diferium", JUICES, 10, List.of(s(DIFERIUM_FUEL_CELL, 1), s(BOOMER_CORE, 50),
                    s(JUMPER_CABLE, 1), s(SPARKPLUG, 1), s(T1_SOCKET_ADAPTER, 1)), 2_500, 0),
            new Recipe("juice-triferium", JUICES, 15, List.of(s(TRIFERIUM_FUEL_CELL, 1), s(BOOMER_CORE, 50),
                    s(JUMPER_CABLE, 1), s(SPARKPLUG, 1), s(T1_SOCKET_ADAPTER, 1)), 2_500, 0));

    // ---- Ridley's Stone (Set 4) ----------------------------------------------------------------------------------
    /** The trophies Ridley takes with a stone, one each. */
    public static final List<Integer> RIDLEY_TROPHIES = List.of(BALROG_CLAW, BIGFOOTS_TOE, ERGOTHS_JAWBONE,
            JACK_O_LANTERN, PAPULATUS_CURL, PIANUS_SCALE, TENGU_NOSE);
    /** The guide's 25 prizes. */
    public static final List<Integer> RIDLEY_PRIZES = List.of(
            2_290_009, // [Mastery Book] Advanced Combo Attack 30
            2_290_045, // [Mastery Book] Ifrit 30
            2_290_025, // [Mastery Book] Mana Reflection 30
            4_161_015, // [Storybook] Burning Book of Fire
            1_032_027, // Black Emerald Earrings
            1_302_026, // Black Umbrella
            1_382_035, // Blue Marine
            1_050_103, // Blue Varuna (M)
            1_122_006, // Bow-tie (Blue)
            1_122_004, // Bow-tie (Pink)
            1_372_010, // Dimon Wand
            1_382_037, // Doomsday Staff
            1_382_036, // Dragon Staff
            1_372_032, // Dragon Wand
            1_032_007, // Emerald Earrings
            1_312_013, // Green Paint Brush
            1_302_027, // Green Umbrella
            1_002_398, // Green Varr Hat
            1_382_008, // Kage
            1_302_028, // Light Purple Umbrella
            1_102_042, // Purple Adventurer Cape
            1_002_395, // Purple Bandana
            1_382_016, // Pyogo Mushroom
            1_032_028, // Red Emerald Earrings
            1_032_046); // Spider Venom Earrings

    /** One recipe per trophy: the stone, that trophy, a prize at random. */
    public static final List<Recipe> RIDLEY = RIDLEY_TROPHIES.stream()
            .map(trophy -> new Recipe("ridley-" + trophy, RIDLEY_PRIZES, 1, List.of(s(RIDLEYS_STONE, 1), s(trophy, 1)),
                    0, 0))
            .toList();

    // ---- Adonis (Set 5) ------------------------------------------------------------------------------------------
    public static final List<Recipe> ADONIS_UPGRADES = List.of(
            fixed("shroud-of-zakum", 1_102_194, s(1_102_193, 1), s(RIDLEYS_BOOK_OF_RITUALS, 1), s(DARK_CRYSTAL, 50),
                    s(NARICAIN_JEWEL, 1), s(LEFAY_JEWEL, 1), s(ZAKUM_DIAMOND, 1)),
            fixed("black-crystal-blade", 1_302_107, s(1_302_106, 1), s(4_131_000, 1), s(4_130_002, 1), s(BLACK_CRYSTAL, 50),
                    s(ZAKUM_DIAMOND, 1)),
            fixed("wrath-of-el-nath", 1_102_192, s(1_102_191, 1), s(4_000_081, 50), s(4_000_080, 10),
                    s(PHAROAHS_WRAPPINGS, 1), s(4_000_057, 10), s(4_000_056, 10), s(ZAKUM_DIAMOND, 1)),
            fixed("hard-hat", 1_002_857, s(1_002_856, 1), s(BOOMER_CORE, 10), s(4_000_376, 5), s(POWER_CRYSTAL, 1),
                    s(SILVER_PLATE, 10), s(ZAKUM_DIAMOND, 1)));

    /** Dragon Toenail, Maple Syrup, Moon Star Chair, 1H axe manual, 1H blunt stimulator, polearm manual, Power Elixir, Pumpkin Spear. */
    public static final List<Integer> DENARI_PRIZES = List.of(1_332_031, 2_022_117, 3_010_014, 4_131_001, 4_130_004,
            4_131_007, 2_000_005, 1_432_013);
    public static final Recipe STONE_DENARI_DRAW = new Recipe("stone-denari", DENARI_PRIZES, 1,
            List.of(s(STONE_DENARI, 1)), 0, 0);

    // ---- Lita Lawless's bounty board (BotStory) ------------------------------------------------------------------
    static final int WINDRAIDER_BADGE = 4_032_007;
    static final int STORMBREAKER_BADGE = 4_032_006;
    static final int NIGHTSHADOW_BADGE = 4_032_009;
    /** Every Set 1 artifact. */
    public static final List<Integer> SET1 = List.of(DARK_MATTER, VORTICULAR_GYRO, BLINKING_DINGBAT, ZETA_RESIDUE,
            BLACK_VERSAL_MATERIA, TARU_TOTEM, MYSTIC_ASTROLABE, ANTELLION_RELIC, NARICAIN_JEWEL, SUBANI_ANKH,
            WHITE_VERSAL_MATERIA);
    /** Every Set 2 piece. */
    public static final List<Integer> SET2 = List.of(ANCIENT_HELM, ANCIENT_ARMOR, ANCIENT_GREAVES, ANCIENT_FACEGUARD,
            ANCIENT_GI, ANCIENT_BOOTS, RIDLEYS_BOOK_OF_RITUALS, STONE_TIGER_HEAD, TYPHON_CREST, LEFAY_JEWEL,
            PHAROAHS_WRAPPINGS, CRYSTAL_SHARD);
    /** The Set 3 and Set 4 pieces nothing else on the server makes. */
    public static final List<Integer> SET34 = List.of(TARU_SPIRIT_FEATHER, RIDLEYS_STONE, TRIFERIUM_FUEL_CELL);
    /** The rare pool the top bounty pays from. */
    public static final List<Integer> RARE = List.of(TARU_SPIRIT_FEATHER, WHITE_VERSAL_MATERIA, RIDLEYS_STONE,
            TRIFERIUM_FUEL_CELL, LEFAY_JEWEL, PHAROAHS_WRAPPINGS);
    public static final List<Recipe> LITA_BOUNTIES = List.of(
            new Recipe("bounty-boomer", SET1, 1, List.of(s(BOOMER_CORE, 40)), 0, 0),
            new Recipe("bounty-windraider", SET2, 1, List.of(s(WINDRAIDER_BADGE, 30)), 0, 0),
            new Recipe("bounty-stormbreaker", SET2, 1, List.of(s(STORMBREAKER_BADGE, 30)), 0, 0),
            new Recipe("bounty-nightshadow", RARE, 1, List.of(s(NIGHTSHADOW_BADGE, 30)), 0, 0));

    // ---- Cryssea's card swap (BotStory) --------------------------------------------------------------------------
    /** Three pieces of one group for one piece of the player's choice from the same group. */
    public static final int SWAP_TAKES = 3;
    public static final List<List<Integer>> SWAP_GROUPS = List.of(SET1, SET2, SET34);

    private ItcgRecipes() {
    }
}
