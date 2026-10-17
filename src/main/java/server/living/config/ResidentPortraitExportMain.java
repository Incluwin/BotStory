package server.living.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import server.living.config.Content.Persona;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exports what the client needs to show each Resident's own look in its NPC window:
 * {@code ResidentPortraitExportMain [content-dir] [export.json] [ids.yaml]}; tools\living-world-client.bat runs it before
 * {@link QuestLogExportMain}, then tools/wz-patcher's {@code residents} command renders the portraits
 * from the client's own Character.wz, so no Resident image ever ships with the repository.
 *
 * <p>The looks are the ones the server wrote to the Residents' characters ({@code living_persona_state}
 * -> {@code characters} and their equipped items), so run it after the server has provisioned the
 * Residents (a boot with {@code living_world.ENABLED}), after {@code RESIDENT_LOOK_SEED} changes and
 * after their gear changes.
 *
 * <p>Ids live in {@code portrait-npc-ids.yaml} next to the content: an id is kept once assigned, a new
 * Resident takes the lowest free one in 9970000-9970998 (9970999 is the quest-marker hitbox), and the
 * file lists only Residents that get a portrait, because the server opens a Resident's NPC window with
 * that id and the client needs the NPC to exist. Personas that set {@code portraitNpcId} themselves or
 * have no character yet are left out. A third argument writes the ids there instead, for a tool that
 * installs them only together with the client files (MapleEdit's {@code living_world_portraits.py}).
 */
public final class ResidentPortraitExportMain {
    static final int ID_MIN = 9970000;
    static final int HITBOX_NPC_ID = 9970999;

    /** A Resident's character as the portrait renders it: equipped items by inventory position (negative). */
    record Worn(int characterId, int skin, int face, int hair, Map<Integer, Integer> equipped) { }

    private ResidentPortraitExportMain() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "scripts/config/living-world");
        Path export = Path.of(args.length > 1 ? args[1] : "target/resident-portraits.json");
        Path idsFile = args.length > 2 ? Path.of(args[2]) : root.resolve(ContentLoader.PORTRAIT_NPC_IDS_FILE);
        LivingContent content;
        try {
            content = ContentLoader.load(root);
        } catch (ContentException failure) {
            System.out.println("ERROR " + failure.getMessage());
            System.exit(1);
            return;
        }
        List<Persona> cast = new ArrayList<>();
        for (Persona persona : content.personas().values()) {
            if (persona.portraitNpcId() <= 0) {
                cast.add(persona);
            }
        }
        Configurator.setRootLevel(Level.WARN); // the connection pool's start-up lines
        Map<String, Worn> looks;
        if (!DatabaseConnection.initializeConnectionPool()) {
            System.out.println("ERROR cannot reach the database (config.yaml / .env, as for the server)");
            System.exit(1);
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            looks = looks(con, cast);
        } catch (SQLException failure) {
            System.out.println("ERROR reading Resident looks: " + failure.getMessage());
            System.exit(1);
            return;
        }
        Map<String, Integer> ids = assignIds(cast, content.portraitNpcIds(), looks);
        writeIds(idsFile, ids);

        List<Map<String, Object>> portraits = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            Worn look = looks.get(entry.getKey());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("persona", entry.getKey());
            out.put("npcId", entry.getValue());
            out.put("name", content.persona(entry.getKey()).name());
            out.put("skin", look.skin());
            out.put("face", look.face());
            out.put("hair", look.hair());
            out.put("equipped", look.equipped());
            portraits.add(out);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("hitboxNpcId", HITBOX_NPC_ID);
        doc.put("portraits", portraits);
        Files.createDirectories(export.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(export.toFile(), doc);
        List<String> missing = new ArrayList<>();
        for (Persona persona : cast) {
            if (!looks.containsKey(persona.id())) {
                missing.add(persona.id());
            }
        }
        System.out.println(portraits.size() + " portraits -> " + export.toAbsolutePath()
                + "; no character yet: " + (missing.isEmpty() ? "none" : String.join(", ", missing)));
        System.out.println("ids in " + idsFile.toAbsolutePath());
        System.exit(0); // the connection pool's threads would keep the JVM up
    }

    /** persona id -> its character's look, for the personas that have a character. */
    static Map<String, Worn> looks(Connection con, Collection<Persona> cast) throws SQLException {
        Map<String, Worn> out = new LinkedHashMap<>();
        String sql = "SELECT c.id, c.skincolor, c.face, c.hair FROM living_persona_state p"
                + " JOIN characters c ON c.id = p.character_id WHERE p.persona_id = ?";
        String equippedSql = "SELECT position, itemid FROM inventoryitems WHERE inventorytype = -1 AND characterid = ?";
        try (PreparedStatement ps = con.prepareStatement(sql); PreparedStatement items = con.prepareStatement(equippedSql)) {
            for (Persona persona : cast) {
                ps.setString(1, persona.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                    int characterId = rs.getInt(1);
                    Map<Integer, Integer> equipped = new TreeMap<>();
                    items.setInt(1, characterId);
                    try (ResultSet worn = items.executeQuery()) {
                        while (worn.next()) {
                            equipped.put(worn.getInt(1), worn.getInt(2));
                        }
                    }
                    out.put(persona.id(), new Worn(characterId, rs.getInt(2), rs.getInt(3), rs.getInt(4), equipped));
                }
            }
        }
        return out;
    }

    /** Existing ids of Residents that get a portrait are kept; the others take the lowest free id in content order. */
    static Map<String, Integer> assignIds(List<Persona> cast, Map<String, Integer> existing, Map<String, Worn> looks) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : existing.entrySet()) {
            if (looks.containsKey(entry.getKey()) && !ids.containsValue(entry.getValue())) {
                ids.put(entry.getKey(), entry.getValue());
            }
        }
        for (Persona persona : cast) {
            if (!looks.containsKey(persona.id()) || ids.containsKey(persona.id())) {
                continue;
            }
            int free = ID_MIN;
            while (ids.containsValue(free)) {
                free++;
            }
            if (free >= HITBOX_NPC_ID) {
                throw new ContentException("out of portrait NPC ids");
            }
            ids.put(persona.id(), free);
        }
        return ids;
    }

    private static void writeIds(Path file, Map<String, Integer> ids) throws IOException {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(ids.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        StringBuilder yaml = new StringBuilder();
        yaml.append("# NPC ids whose Npc.wz/String.wz entries show each Resident's own look in its NPC window.\n");
        yaml.append("# Generated by tools\\living-world-client.bat (ResidentPortraitExportMain); ids are stable once assigned.\n");
        yaml.append("ids:\n");
        for (Map.Entry<String, Integer> entry : sorted) {
            yaml.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        Files.writeString(file, yaml.toString(), StandardCharsets.US_ASCII);
    }
}
