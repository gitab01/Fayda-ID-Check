package id.fayda.verification.service;

import id.fayda.verification.infra.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The identity hash is the only place a national id could leak into storage, so its shape is a test. */
class HashServiceTest {

    private static final String ID = "1234567890126";

    private final HashService hashes = new HashService(new AppProperties());

    @Test
    void producesADigestAndItsPerRowSalt() {
        HashService.SaltedHash salted = hashes.hash(ID);

        assertThat(salted.hash()).matches("[0-9a-f]{64}");
        assertThat(salted.salt()).matches("[0-9a-f]{32}");
        assertThat(hashes.matches(ID, salted.salt(), salted.hash())).isTrue();
    }

    @Test
    void twoHashesOfTheSameIdDifferBecauseTheSaltIsRandom() {
        HashService.SaltedHash first = hashes.hash(ID);
        HashService.SaltedHash second = hashes.hash(ID);

        assertThat(first.hash()).isNotEqualTo(second.hash());
        assertThat(hashes.matches(ID, first.salt(), second.hash())).isFalse();
    }

    @Test
    void anotherIdNeverMatchesAStoredDigest() {
        HashService.SaltedHash salted = hashes.hash(ID);

        assertThat(hashes.matches("1234567890127", salted.salt(), salted.hash())).isFalse();
        assertThat(hashes.matches(ID, salted.salt(), salted.hash().toUpperCase())).isTrue();
    }

    @Test
    void thePepperChangesTheDigestWithoutChangingTheStoredSalt() {
        AppProperties other = new AppProperties();
        other.getHashing().setPepper("a-completely-different-pepper");
        HashService peppered = new HashService(other);

        HashService.SaltedHash salted = hashes.hash(ID);
        String withOtherPepper = peppered.recompute(ID, salted.salt());

        assertThat(withOtherPepper).isNotEqualTo(salted.hash());
    }

    @Test
    void theRawValueAppearsNowhereInTheDigestOrTheSalt() {
        HashService.SaltedHash salted = hashes.hash(ID);

        assertThat(salted.hash()).doesNotContain(ID);
        assertThat(salted.salt()).doesNotContain(ID);
    }

    @Test
    void refusesToHashAnEmptyValue() {
        assertThatThrownBy(() -> hashes.hash("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hashes.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256HexIsPlainDigestForNonIdentityUse() {
        assertThat(hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(HashService.sha256Hex(new byte[]{0x61, 0x62, 0x63}))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
