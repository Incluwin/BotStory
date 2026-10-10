package server.living.resident;

import client.Client;
import client.creator.BotCreator;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import server.agents.auth.AgentControlService;
import server.agents.integration.AgentAccountResolution;
import server.agents.integration.AgentBackingAccountSecurityRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentPersistenceGateway;
import server.agents.integration.AgentPersistenceGatewayRuntime;
import server.agents.integration.cosmic.CosmicAgentClientGateway;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A Resident's backing character starts in its own gender's beginner clothes, not the men's set: ResidentProvisioner
 * through the Cosmic gateway to BotCreator, with account and character creation stubbed (no database). The clothes are
 * read from the creation call (face, hair, skin, gender, top, bottom, shoes, weapon). The GM spawn path keeps the men's
 * set, and both sets are the game's own creation choices for their gender (Etc.wz MakeCharInfo CharMale/CharFemale).
 */
class ResidentStarterOutfitTest {
    private static final int[] MENS_SET = {20100, 30020, 0, 0, 1040002, 1060002, 1072001, 1302000};

    private final List<int[]> created = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final LivingWorld world = mock(LivingWorld.class);

    @BeforeEach
    void wire() throws Exception {
        LivingStore store = mock(LivingStore.class);
        when(store.personaState(anyString())).thenAnswer(inv -> new LivingStore.PersonaState(inv.getArgument(0), 0, null, 0, 0));
        when(world.store()).thenReturn(store);
        when(world.config()).thenReturn(new LivingWorldConfig());
        AgentControlService control = mock(AgentControlService.class);
        track(mockStatic(AgentControlService.class)).when(AgentControlService::getInstance).thenReturn(control);
        AgentPersistenceGateway persistence = mock(AgentPersistenceGateway.class);
        when(persistence.resolveOrCreateAgentAccount(anyString())).thenReturn(AgentAccountResolution.created(77));
        track(mockStatic(AgentPersistenceGatewayRuntime.class)).when(AgentPersistenceGatewayRuntime::persistence).thenReturn(persistence);
        track(mockStatic(AgentBackingAccountSecurityRuntime.class))
                .when(() -> AgentBackingAccountSecurityRuntime.lockInteractiveLogin(anyInt())).thenReturn(true);
        // the real Cosmic gateway, without building a real BotClient
        AgentClientGateway clients = mock(AgentClientGateway.class, AdditionalAnswers.delegatesTo(CosmicAgentClientGateway.INSTANCE));
        doReturn(mock(Client.class)).when(clients).createHeadlessClient(anyInt(), anyInt());
        track(mockStatic(AgentClientGatewayRuntime.class)).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        track(mockStatic(AgentIdentityGatewayRuntime.class)).when(AgentIdentityGatewayRuntime::identities)
                .thenReturn(mock(AgentIdentityGateway.class));
        MockedStatic<BotCreator> creator = track(mockStatic(BotCreator.class));
        creator.when(() -> BotCreator.createCharacter(any(), anyString())).thenCallRealMethod();
        creator.when(() -> BotCreator.createCharacter(any(), anyString(), anyInt())).thenCallRealMethod();
        creator.when(() -> BotCreator.createCharacter(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt())).thenAnswer(inv -> {
                    int[] look = new int[8];
                    for (int i = 0; i < look.length; i++) {
                        look[i] = inv.getArgument(i + 2);
                    }
                    created.add(look);
                    return 900 + created.size();
                });
    }

    @AfterEach
    void close() {
        statics.forEach(MockedStatic::close);
    }

    @Test
    void aFemaleResidentStartsInWomensOrUnisexClothes() {
        int[] look = provision(persona("tamsin", new Look("female", -1, 0, 0)));
        assertEquals(1, look[3], "gender");
        assertTrue(look[0] / 1000 == 21, "face " + look[0]);
        assertTrue(look[1] / 1000 == 31 || look[1] / 1000 == 34, "hair " + look[1]);
        for (int slot = 4; slot < 8; slot++) {
            assertTrue(wearable(look[slot], 1), "a woman's starter item " + look[slot] + " is a man's");
        }
    }

    @Test
    void aMaleResidentKeepsTheMensSet() {
        assertArrayEquals(MENS_SET, provision(persona("garrick", new Look("male", -1, 0, 0))));
    }

    @Test
    void aResidentWithNoAuthoredGenderStartsInTheGenderItsLookDraws() {
        ResidentLooks looks = new ResidentLooks();
        Set<Integer> genders = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            Persona persona = persona("resident" + i, Look.NONE);
            int[] look = provision(persona);
            int gender = looks.resolve(persona).gender();
            assertEquals(gender, look[3], persona.id());
            for (int slot = 4; slot < 8; slot++) {
                assertTrue(wearable(look[slot], gender), persona.id() + " (gender " + gender + ") starts in " + look[slot]);
            }
            genders.add(gender);
        }
        assertEquals(Set.of(0, 1), genders, "40 drawn looks cover both genders");
    }

    @Test
    void theGmSpawnPathKeepsTheMensSet() {
        CosmicAgentClientGateway.INSTANCE.createBackingCharacter(mock(Client.class), "Spawned");
        assertEquals(1, created.size());
        assertArrayEquals(MENS_SET, created.get(0));
    }

    @Test
    void bothStarterSetsAreTheGamesOwnCreationChoicesForTheirGender() throws Exception {
        Element info = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz/Etc.wz/MakeCharInfo.img.xml").toFile()).getDocumentElement();
        int[] female = provision(persona("f", new Look("female", -1, 0, 0)));
        int[] male = provision(persona("m", new Look("male", -1, 0, 0)));
        // MakeCharInfo choice lists: 4 top, 5 bottom, 6 shoes, 7 weapon
        for (int list = 4; list < 8; list++) {
            assertTrue(choices(info, "CharFemale", list).contains(female[list]), "CharFemale/" + list + " lacks " + female[list]);
            assertTrue(choices(info, "CharMale", list).contains(male[list]), "CharMale/" + list + " lacks " + male[list]);
        }
    }

    private int[] provision(Persona persona) {
        int before = created.size();
        int id = new ResidentProvisioner(world).ensureCharacter(persona);
        assertTrue(id > 0, persona.id() + " was not provisioned");
        assertEquals(before + 1, created.size(), "one character created for " + persona.id());
        return created.get(before);
    }

    /** The gender digit of an equip id ({@code id / 1000 % 10}): 0 male only, 1 female only, anything else both. */
    private static boolean wearable(int itemId, int gender) {
        int digit = itemId / 1000 % 10;
        return digit > 1 || digit == gender;
    }

    /** The ids of {@code Info/<table>/<list>} in MakeCharInfo. */
    private static Set<Integer> choices(Element root, String table, int list) {
        Set<Integer> ids = new HashSet<>();
        Element tableDir = child(child(root, "Info"), table);
        NodeList values = child(tableDir, String.valueOf(list)).getElementsByTagName("int");
        for (int i = 0; i < values.getLength(); i++) {
            ids.add(Integer.parseInt(((Element) values.item(i)).getAttribute("value")));
        }
        return ids;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        throw new AssertionError("MakeCharInfo has no " + name + " under " + parent.getAttribute("name"));
    }

    private static Persona persona(String id, Look look) {
        return new Persona(id, id, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, look, "test");
    }

    private <T> MockedStatic<T> track(MockedStatic<T> mocked) {
        statics.add(mocked);
        return mocked;
    }
}
