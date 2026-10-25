package config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** GPQ plan decision 9's living_world key: COMPANION_GUILD_COFOUNDERS_ENABLED, on, with its reasons in config.yaml. */
class LivingWorldConfigGuildCofounderTest {

    @Test
    void companionsCofoundGuildsByDefault() {
        assertTrue(new LivingWorldConfig().COMPANION_GUILD_COFOUNDERS_ENABLED);
    }

    @Test
    void configYamlShipsTheKeyAndSaysWhy() throws IOException {
        String yaml = new String(Files.readAllBytes(Path.of("config.yaml")), StandardCharsets.US_ASCII);
        assertTrue(yaml.contains("\n    COMPANION_GUILD_COFOUNDERS_ENABLED: true\n"));
        String comment = yaml.substring(yaml.indexOf("# A player founds a guild with their companions"),
                yaml.indexOf("COMPANION_GUILD_COFOUNDERS_ENABLED:"));
        for (String said : new String[] {"kill switch", "Jr. Master", "cofounder", "CompanionGuildFounding"}) {
            assertTrue(comment.replace("\n    #", "").contains(said), said + " in: " + comment);
        }
    }
}
