package server.mods;

/**
 * A server mod: one switch in config.yaml {@code mods.SWITCHES} (or the {@code MOD_<ID>} environment variable), read at
 * boot and flippable by {@code !mods} until the next restart. Code that a mod changes asks {@link ServerMods#isOn}
 * before it acts.
 *
 * @param id        snake_case, the config.yaml key
 * @param title     what {@code !mods} shows
 * @param defaultOn the switch when config.yaml has no line for it
 * @param bootOnly  {@code !mods} does not flip it: connected clients already depend on its boot value
 */
public record ServerMod(String id, String title, boolean defaultOn, boolean bootOnly) {
    public ServerMod(String id, String title, boolean defaultOn) {
        this(id, title, defaultOn, false);
    }
}
