package tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    // Known-good digests for the literal string "password". These fixtures
    // are load-bearing: if they ever change, every legacy account stored by
    // the pre-bcrypt server would fail to migrate on next login.
    private static final String PW = "password";
    private static final String PW_SHA1_HEX = "5baa61e4c9b93f3f0682250b6cf8331b7ee68fd8";
    private static final String PW_SHA512_HEX =
            "b109f3bbbc244eb82441917ed06d618b9008dd09b3befd1b5e07394c706a8bb9"
                    + "80b1d7785e5976ec049b46df5f1326af5a2ea6d103fd07c95385ffab0cacbc86";

    @Test
    void hashSha512HexMatchesKnownFixture() {
        assertEquals(PW_SHA512_HEX, PasswordHasher.hashSha512Hex(PW));
    }

    @Test
    void matchesLegacyHashSha512() {
        assertTrue(PasswordHasher.matchesLegacyHash(PW_SHA512_HEX, "SHA-512", PW));
        assertFalse(PasswordHasher.matchesLegacyHash(PW_SHA512_HEX, "SHA-512", "wrong"));
    }

    @Test
    void matchesLegacyHashSha1() {
        assertTrue(PasswordHasher.matchesLegacyHash(PW_SHA1_HEX, "SHA-1", PW));
        assertFalse(PasswordHasher.matchesLegacyHash(PW_SHA1_HEX, "SHA-1", "wrong"));
    }

    @Test
    void bcryptRoundTrip() {
        String hash = PasswordHasher.hashWithBcrypt(PW);
        assertTrue(PasswordHasher.isBcryptHash(hash));
        assertTrue(PasswordHasher.checkBcrypt(PW, hash));
        assertFalse(PasswordHasher.checkBcrypt("wrong", hash));
    }

    @Test
    void isBcryptHashRecognisesPrefix() {
        assertTrue(PasswordHasher.isBcryptHash("$2a$12$somethinglongenough"));
        assertTrue(PasswordHasher.isBcryptHash("$2b$10$otherversion"));
        assertFalse(PasswordHasher.isBcryptHash(PW_SHA512_HEX));
        assertFalse(PasswordHasher.isBcryptHash("$1$md5crypt"));
        assertFalse(PasswordHasher.isBcryptHash(""));
        assertFalse(PasswordHasher.isBcryptHash(null));
    }

    @Test
    void bcryptHashesAreSaltedUniquely() {
        String a = PasswordHasher.hashWithBcrypt(PW);
        String b = PasswordHasher.hashWithBcrypt(PW);
        assertFalse(a.equals(b), "bcrypt hashes of the same password must differ (salt)");
        assertTrue(PasswordHasher.checkBcrypt(PW, a));
        assertTrue(PasswordHasher.checkBcrypt(PW, b));
    }
}
