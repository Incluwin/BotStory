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
    public LivingWorldConfig living_world;
    public PopulationConfig population;
    public KnowledgeConfig knowledge;
    public ModsConfig mods;
    public EconomyConfig economy;

    private static YamlConfig loadConfig() {
        try {
            YamlReader reader = new YamlReader(Files.newBufferedReader(Path.of(CONFIG_FILE_NAME), CharsetConstants.CHARSET));
            YamlConfig config = reader.read(YamlConfig.class);
            reader.close();
            config.server.applyAddressOverrides(System.getenv());
            if (config.living_world == null) {
                config.living_world = new LivingWorldConfig();
            }
            config.living_world.validate();
            if (config.population == null) {
                config.population = new PopulationConfig();
            }
            config.population.validate();
            if (config.knowledge == null) {
                config.knowledge = new KnowledgeConfig();
            }
            config.knowledge.validate();
            if (config.mods == null) {
                config.mods = new ModsConfig();
            }
            config.mods.validate();
            if (config.economy == null) {
                config.economy = new EconomyConfig();
            }
            config.economy.validate();
            return config;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not read config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Could not successfully parse config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage(), e);
        }
    }
}
