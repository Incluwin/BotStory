package config;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.string.CharsetConstants;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


public class YamlConfig {
    public static final String CONFIG_FILE_NAME = "config.yaml";
    public static final YamlConfig config = loadConfig();

    public List<WorldConfig> worlds;
    public ServerConfig server;
    public BotEconomyConfig bot_economy;
    public LivingWorldConfig living_world;

    private static YamlConfig loadConfig() {
        try {
            YamlReader reader = new YamlReader(Files.newBufferedReader(Path.of(CONFIG_FILE_NAME), CharsetConstants.CHARSET));
            YamlConfig config = reader.read(YamlConfig.class);
            reader.close();
            if (config.bot_economy == null) {
                // Missing block in config.yaml: initialize with defaults. Feature stays
                // OFF until ENABLED is explicitly flipped to true.
                config.bot_economy = new BotEconomyConfig();
            }
            config.bot_economy.validate();
            if (config.living_world == null) {
                config.living_world = new LivingWorldConfig();
            }
            config.living_world.validate();
            return config;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not read config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Could not successfully parse config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage(), e);
        }
    }
}
