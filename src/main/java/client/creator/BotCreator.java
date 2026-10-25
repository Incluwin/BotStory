package client.creator;

import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

/**
 * Creates bot/companion characters server-side using the same Character.getDefault +
 * insertNewChar pipeline as normal character creation, but without the client-packet
 * validation (MakeCharInfoValidator) which is irrelevant for server-initiated creation.
 */
public class BotCreator extends CharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(BotCreator.class);

    /** A starting look: face, hair and the beginner clothes and weapon. */
    private record StarterLook(int face, int hair, int top, int bottom, int shoes, int weapon) { }

    /**
     * Each gender's starting look; the clothes and weapon are stock creation choices of that gender (Etc.wz
     * MakeCharInfo Info/CharMale, Info/CharFemale), so a woman never starts in the men's White Undershirt and Blue
     * Jean Shorts. Female: White Tubetop, Red Miniskirt, the first female face and hair.
     */
    private static final StarterLook MALE_START = new StarterLook(20100, 30020, 1040002, 1060002, 1072001, 1302000);
    private static final StarterLook FEMALE_START = new StarterLook(21000, 31000, 1041002, 1061002, 1072001, 1302000);

    /**
     * The starting top or bottom a wearer of {@code gender} (0 male, 1 female) takes for {@code itemId} when it is the
     * other gender's starting top or bottom (MALE_START / FEMALE_START): its own set's piece for that slot. 0 for anything
     * else: its own gender's piece, the boots and sword both sets share, any other item or gender.
     */
    public static int starterTwin(int itemId, int gender) {
        if (gender != 0 && gender != 1) {
            return 0;
        }
        StarterLook own = gender == 1 ? FEMALE_START : MALE_START;
        StarterLook other = gender == 1 ? MALE_START : FEMALE_START;
        if (itemId == other.top() && other.top() != own.top()) {
            return own.top();
        }
        if (itemId == other.bottom() && other.bottom() != own.bottom()) {
            return own.bottom();
        }
        return 0;
    }

    /**
     * An Aran's starting clothes and Basic Polearm, the same for both genders (Etc.wz MakeCharInfo OrientCharMale,
     * OrientCharFemale). A Noblesse starts in the Beginner's clothes (PremiumChar).
     */
    public static final int ARAN_TOP = 1042167;
    public static final int ARAN_BOTTOM = 1062115;
    public static final int ARAN_SHOES = 1072383;
    public static final int BASIC_POLEARM = 1442079;

    public static int createCharacter(Client c, String name) {
        return createCharacter(c, name, 0);
    }

    /** A character of this gender (0 male, 1 female) in that gender's starting look. */
    public static int createCharacter(Client c, String name, int gender) {
        return createCharacter(c, name, gender, Job.BEGINNER);
    }

    /**
     * A character of this gender starting {@code beginner}'s line: a Beginner, a Noblesse (Cygnus Knights) or a Legend
     * (Aran), in that line's starting look.
     */
    public static int createCharacter(Client c, String name, int gender, Job beginner) {
        StarterLook look = gender == 1 ? FEMALE_START : MALE_START;
        if (beginner == Job.LEGEND) {
            return createCharacter(c, name, look.face(), look.hair(), 0, gender,
                    ARAN_TOP, ARAN_BOTTOM, ARAN_SHOES, BASIC_POLEARM, beginner);
        }
        return createCharacter(c, name, look.face(), look.hair(), 0, gender,
                look.top(), look.bottom(), look.shoes(), look.weapon(), beginner);
    }

    public static int createCharacter(Client c,
                                      String name,
                                      int face,
                                      int hair,
                                      int skin,
                                      int gender,
                                      int topId,
                                      int bottomId,
                                      int shoesId,
                                      int weaponId) {
        return createCharacter(c, name, face, hair, skin, gender, topId, bottomId, shoesId, weaponId, Job.BEGINNER);
    }

    /**
     * Where {@code beginner}'s line begins: a Beginner in Henesys (bots skip Maple Island), a Noblesse in Ereve's Forest
     * of Beginning, a Legend in Rien (the Black Road tutorial is a cutscene; the story wakes it on Snow Island).
     */
    public static int startMapOf(Job beginner) {
        if (beginner == Job.NOBLESSE) {
            return MapId.STARTING_MAP_NOBLESSE;
        }
        return beginner == Job.LEGEND ? MapId.RIEN : MapId.HENESYS;
    }

    /** {@code beginner}: BEGINNER, NOBLESSE or LEGEND (anything else is made a Beginner). */
    public static int createCharacter(Client c,
                                      String name,
                                      int face,
                                      int hair,
                                      int skin,
                                      int gender,
                                      int topId,
                                      int bottomId,
                                      int shoesId,
                                      int weaponId,
                                      Job beginner) {
        Job job = beginner == Job.NOBLESSE || beginner == Job.LEGEND ? beginner : Job.BEGINNER;
        int mapId = startMapOf(job);
        if (!Character.canCreateChar(name)) {
            log.warn("Bot creation rejected — invalid name '{}'", name);
            return -1;
        }

        Character botChar = Character.getDefault(c);
        botChar.setWorld(c.getWorld());
        botChar.setSkinColor(SkinColor.getById(skin));
        botChar.setGender(gender);
        botChar.setName(name);
        botChar.setHair(hair);
        botChar.setFace(face);
        botChar.setJob(job);
        botChar.setLevel(1);
        botChar.setMapId(mapId);

        // Equip standard beginner starting gear (mirrors CharacterFactory.createNewCharacter)
        Inventory equipped = botChar.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        Item top = ii.getEquipById(topId);
        top.setPosition((byte) -5);
        equipped.addItemFromDB(top);

        Item bottom = ii.getEquipById(bottomId);
        bottom.setPosition((byte) -6);
        equipped.addItemFromDB(bottom);

        Item shoes = ii.getEquipById(shoesId);
        shoes.setPosition((byte) -7);
        equipped.addItemFromDB(shoes);

        Item weapon = ii.getEquipById(weaponId);
        weapon.setPosition((byte) -11);
        equipped.addItemFromDB(weapon.copy());

        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(
                job, 1, mapId, topId, bottomId, shoesId, weaponId);

        if (!botChar.insertNewChar(recipe)) {
            log.error("insertNewChar failed for bot '{}'", name);
            return -1;
        }

        log.info("Bot character '{}' created for account id {}", name, c.getAccID());
        return botChar.getId();
    }
}
