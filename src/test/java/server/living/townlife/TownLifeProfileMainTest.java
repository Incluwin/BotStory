package server.living.townlife;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLifeProfileMainTest {
    @TempDir
    Path dir;

    private void index(String... profiles) throws Exception {
        StringBuilder files = new StringBuilder();
        for (String profile : profiles) {
            String[] parts = profile.split("=");
            Files.writeString(dir.resolve(parts[1] + ".json"),
                    "{ \"profileId\": \"" + parts[1] + "\", \"mapId\": " + parts[0] + " }");
            files.append(files.isEmpty() ? "" : ", ").append('"').append(parts[1]).append(".json\"");
        }
        Files.writeString(dir.resolve("index.json"), "{ \"profiles\": [" + files + "] }");
    }

    @Test
    void neverReplacesAHandAuthoredProfile() throws Exception {
        index("100000000=henesys");
        String before = Files.readString(dir.resolve("henesys.json"));

        assertEquals(1, TownLifeProfileMain.generate(List.of("100000000", "--replace"), dir));
        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=henesys"), dir));
        assertEquals(before, Files.readString(dir.resolve("henesys.json")));
    }

    @Test
    void replaceKeepsTheIndexedProfileIdAndFile() throws Exception {
        index("610030000=crimsonwood-keep");

        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000"), dir));
        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=courtyard", "--replace"), dir));
        assertEquals(0, TownLifeProfileMain.generate(List.of("610030000", "--replace", "--index"), dir));
        assertTrue(Files.readString(dir.resolve("crimsonwood-keep.json")).contains("\"profileId\": \"crimsonwood-keep\""));
        assertFalse(Files.exists(dir.resolve("courtyard.json")), "the String.wz slug must not become a second profile");
        assertEquals("{ \"profiles\": [\"crimsonwood-keep.json\"] }", Files.readString(dir.resolve("index.json")));
    }

    @Test
    void neverWritesOverAFileThatIsNotAProfileOfTheMap() throws Exception {
        index("100000001=keep-x");
        Files.writeString(dir.resolve("ambient-deployment.json"), "{ \"towns\": [] }");
        String before = Files.readString(dir.resolve("index.json"));

        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=keep-x"), dir));
        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=index"), dir));
        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=ambient-deployment"), dir));
        assertEquals(before, Files.readString(dir.resolve("index.json")));
        assertEquals("{ \"towns\": [] }", Files.readString(dir.resolve("ambient-deployment.json")));
    }

    @Test
    void neverIndexesARepeatedProfileId() throws Exception {
        Files.writeString(dir.resolve("a.json"), "{ \"profileId\": \"same\", \"mapId\": 1 }");
        Files.writeString(dir.resolve("b.json"), "{ \"profileId\": \"same\", \"mapId\": 2 }");
        String index = "{ \"profiles\": [\"a.json\", \"b.json\"] }";
        Files.writeString(dir.resolve("index.json"), index);

        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=keep-y", "--index"), dir));
        assertEquals(index, Files.readString(dir.resolve("index.json")));
    }

    @Test
    void refusesTheSameMapTwiceInOneRun() throws Exception {
        assertEquals(1, TownLifeProfileMain.generate(List.of("610030000=keep-a", "610030000=keep-b"), dir));
        assertTrue(Files.exists(dir.resolve("keep-a.json")));
        assertFalse(Files.exists(dir.resolve("keep-b.json")));
    }

    @Test
    void rejectsBadArguments() throws Exception {
        assertEquals(2, TownLifeProfileMain.generate(List.of("610030000=../escape"), dir));
        assertEquals(2, TownLifeProfileMain.generate(List.of("--admision", "AMBIENT", "610030000"), dir));
        assertEquals(2, TownLifeProfileMain.generate(List.of("610030000", "--out"), dir));
        assertEquals(2, TownLifeProfileMain.generate(List.of(), dir));
        assertFalse(Files.exists(dir.resolve("index.json")));
    }
}
