package server.living.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quest-log-blocks.yaml, the one list of who takes client quest-log ids where: the blocks lie in Quest.wz's custom
 * range, no two overlap, and every id quest-log-ids.yaml hands out lies in one. The Repack towns' Residents were once
 * given 31750-32199 (docs/REPACK_IMPORT_PLAN.md section 6), over F6's 31600-31999 and the Residents' 32000-32149.
 */
class QuestLogIdBlocksTest {
    private static final Path ROOT = Path.of("scripts/config/living-world");
    private static final Pattern BLOCK = Pattern.compile("^ {2}(\\d+)-(\\d+): (.+)$");

    private record Block(int from, int to, String owner) {
        boolean holds(int id) {
            return id >= from && id <= to;
        }
    }

    @Test
    void theBlocksLieInTheCustomRangeAndNoTwoOverlap() throws Exception {
        List<Block> blocks = blocks();
        assertTrue(blocks.size() >= 11, "the blocks load: " + blocks);
        Block before = null;
        for (Block block : blocks) {
            assertTrue(block.from() >= ContentLint.QUEST_LOG_ID_MIN && block.to() <= ContentLint.QUEST_LOG_ID_MAX
                    && block.from() <= block.to(), block + " in " + ContentLint.QUEST_LOG_ID_MIN + "-"
                    + ContentLint.QUEST_LOG_ID_MAX);
            assertTrue(before == null || block.from() > before.to(), block + " overlaps " + before);
            before = block;
        }
    }

    @Test
    void everyIdHandedOutLiesInABlock() throws Exception {
        List<Block> blocks = blocks();
        List<String> outside = new ArrayList<>();
        ContentLoader.load(ROOT).questLogIds().forEach((quest, id) -> {
            if (blocks.stream().noneMatch(block -> block.holds(id))) {
                outside.add(quest + " -> " + id);
            }
        });
        assertEquals(List.of(), outside, "quest-log ids in no block of quest-log-blocks.yaml");
    }

    @Test
    void theRepackTownsTakeTheirOwnBlock() throws Exception {
        Block repack = blocks().stream().filter(block -> block.owner().contains("Repack")).findFirst().orElseThrow();
        assertEquals(new Block(32150, 32599, repack.owner()), repack);
        assertTrue(Files.readString(Path.of("docs/REPACK_IMPORT_PLAN.md")).contains("quest-log ids 32150-32599"),
                "the Repack plan names its block");
    }

    /** The blocks in file order, which is id order. */
    private static List<Block> blocks() throws Exception {
        List<Block> blocks = new ArrayList<>();
        for (String line : Files.readAllLines(ROOT.resolve("quest-log-blocks.yaml"))) {
            Matcher matcher = BLOCK.matcher(line);
            if (matcher.matches()) {
                blocks.add(new Block(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        matcher.group(3)));
            }
        }
        return blocks;
    }
}
