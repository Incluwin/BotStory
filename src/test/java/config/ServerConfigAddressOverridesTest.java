package config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigAddressOverridesTest {

    @Test
    void environmentAddressesReplaceTheConfiguredOnes() {
        ServerConfig config = configured();

        config.applyAddressOverrides(Map.of(
                "SERVER_HOST", " 203.0.113.7 ",
                "SERVER_LANHOST", "192.168.1.20",
                "SERVER_TAILSCALEHOST", "100.64.0.9"));

        assertEquals("203.0.113.7", config.HOST);
        assertEquals("192.168.1.20", config.LANHOST);
        assertEquals("100.64.0.9", config.TAILSCALEHOST);
        assertEquals("127.0.0.1", config.LOCALHOST, "loopback players always get LOCALHOST");
    }

    @Test
    void missingOrBlankVariablesKeepConfigYaml() {
        ServerConfig config = configured();

        config.applyAddressOverrides(Map.of("SERVER_HOST", "  ", "SERVER_LANHOST", ""));

        assertEquals("127.0.0.1", config.HOST);
        assertEquals("127.0.0.1", config.LANHOST);
        assertEquals("127.0.0.1", config.TAILSCALEHOST);
    }

    private static ServerConfig configured() {
        ServerConfig config = new ServerConfig();
        config.HOST = "127.0.0.1";
        config.LANHOST = "127.0.0.1";
        config.LOCALHOST = "127.0.0.1";
        config.TAILSCALEHOST = "127.0.0.1";
        return config;
    }
}
