package server.living.resident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * Each Resident's appearance: whatever the persona's {@code look} block sets, and for the rest a
 * pick that is stable for the persona id - gender, a natural skin tone, a regular face and
 * hairstyle for that gender, and an eye and hair color. Styles come from this server's
 * Character.wz, which every client's Character.wz contains. The look is written to the backing
 * character while it is not loaded, so the Resident materializes with it and the portrait tool
 * (MapleEdit/app/living_world_portraits.py) reads it from the database.
 */
public final class ResidentLooks {
    private static final Logger log = LoggerFactory.getLogger(ResidentLooks.class);
    private static final int[] SKINS = {0, 1, 2, 3};

    public record Resolved(int gender, int skin, int face, int hair) { }

    private final TreeSet<Integer> faces = new TreeSet<>();
    private final TreeSet<Integer> hairs = new TreeSet<>();

    ResidentLooks() {
        for (DataDirectoryEntry dir : DataProviderFactory.getDataProvider(WZFiles.CHARACTER).getRoot().getSubdirectories()) {
            TreeSet<Integer> into = dir.getName().equals("Face") ? faces : dir.getName().equals("Hair") ? hairs : null;
            if (into == null) {
                continue;
            }
            for (DataFileEntry file : dir.getFiles()) {
                try {
                    into.add(Integer.parseInt(file.getName().substring(0, file.getName().indexOf('.'))));
                } catch (RuntimeException ignored) {
                    // not an id-named img
                }
            }
        }
    }

    public Resolved resolve(Persona persona) {
        Look look = persona.look();
        // every draw happens in a fixed order so setting one field never reshuffles the others
        SplittableRandom random = draws(persona);
        int genderDraw = random.nextInt(2);
        int skinDraw = random.nextInt(SKINS.length);
        int faceDraw = random.nextInt(Integer.MAX_VALUE);
        int eyeDraw = random.nextInt(Integer.MAX_VALUE);
        int hairDraw = random.nextInt(Integer.MAX_VALUE);
        int colorDraw = random.nextInt(Integer.MAX_VALUE);
        int gender = gender(look, genderDraw);
        int skin = look.skin() >= 0 ? look.skin() : SKINS[skinDraw];
        int face = look.face() > 0 ? look.face() : pick(faces, faceStyles(gender), faceDraw, eyeDraw, 100, 9);
        int hair = look.hair() > 0 ? look.hair() : pick(hairs, hairStyles(gender), hairDraw, colorDraw, 1, 8);
        return new Resolved(gender, skin, face, hair);
    }

    /**
     * The gender {@link #resolve} gives the persona (0 male, 1 female), without reading Character.wz: the provisioner
     * creates the character in that gender's starting clothes.
     */
    static int gender(Persona persona) {
        return gender(persona.look(), draws(persona).nextInt(2));
    }

    private static int gender(Look look, int genderDraw) {
        return look.gender() == null ? genderDraw : look.gender().equals("female") ? 1 : 0;
    }

    private static SplittableRandom draws(Persona persona) {
        return new SplittableRandom(0x9E3779B97F4A7C15L ^ persona.id().hashCode());
    }

    /** Regular faces of a gender in their first eye color: 20000-20099 male, 21000-21099 female. */
    private List<Integer> faceStyles(int gender) {
        int base = gender == 1 ? 21000 : 20000;
        return new ArrayList<>(faces.subSet(base, base + 100));
    }

    /** Regular hairstyles of a gender in their first color: 30000s/33000s male, 31000s/34000s female. */
    private List<Integer> hairStyles(int gender) {
        List<Integer> out = new ArrayList<>();
        for (int base : gender == 1 ? new int[] {31000, 34000} : new int[] {30000, 33000}) {
            for (int id : hairs.subSet(base, base + 1000)) {
                if (id % 10 == 0) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** A style, then one of the color variants (style + step * n) that exists. */
    private static int pick(TreeSet<Integer> existing, List<Integer> styles, int styleDraw, int colorDraw, int step, int colors) {
        if (styles.isEmpty()) {
            return 0;
        }
        int style = styles.get(Math.floorMod(styleDraw, styles.size()));
        List<Integer> variants = new ArrayList<>();
        for (int n = 0; n < colors; n++) {
            if (existing.contains(style + step * n)) {
                variants.add(style + step * n);
            }
        }
        return variants.get(Math.floorMod(colorDraw, variants.size()));
    }

    /**
     * Writes the look to the character row when it differs; false when unchanged, the character is loaded, or it is a
     * hired citizen's (its face is its own).
     */
    boolean apply(Persona persona, int characterId) {
        if (characterId <= 0 || LivingContent.isHire(persona.id()) || AgentRuntimeRegistry.findByAgentCharacterId(characterId) != null) {
            return false;
        }
        Resolved look = resolve(persona);
        if (look.face() == 0 || look.hair() == 0) {
            return false;
        }
        String sql = "UPDATE characters SET gender = ?, skincolor = ?, face = ?, hair = ? WHERE id = ?"
                + " AND (gender <> ? OR skincolor <> ? OR face <> ? OR hair <> ?)";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            for (int pass = 0; pass < 2; pass++) {
                ps.setInt(i++, look.gender());
                ps.setInt(i++, look.skin());
                ps.setInt(i++, look.face());
                ps.setInt(i++, look.hair());
                if (pass == 0) {
                    ps.setInt(i++, characterId);
                }
            }
            boolean changed = ps.executeUpdate() > 0;
            if (changed) {
                log.info("Resident {} look: gender {} skin {} face {} hair {}", persona.id(), look.gender(), look.skin(),
                        look.face(), look.hair());
            }
            return changed;
        } catch (SQLException failure) {
            log.warn("Resident {}: writing look failed", persona.id(), failure);
            return false;
        }
    }
}
