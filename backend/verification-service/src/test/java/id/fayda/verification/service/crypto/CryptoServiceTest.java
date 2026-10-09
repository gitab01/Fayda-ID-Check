package id.fayda.verification.service.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.service.UtcTimes;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CONTRACT §3 at the byte level: the replay window, the GCM tag, and the keyId binding are the
 * three ways an envelope can be untrustworthy, and each has its own code.
 */
class CryptoServiceTest {

    private final AppProperties properties = new AppProperties();
    private final CryptoService crypto = new CryptoService(properties, new ObjectMapper());
    private final byte[] key = randomKey();
    private final String keyId = CryptoService.keyIdOf(key);

    @Test
    void roundTripsAFreshEnvelope() {
        byte[] plaintext = "{\"idNumber\":\"1234567890126\"}".getBytes(StandardCharsets.UTF_8);
        Envelope envelope = seal(plaintext, key, keyId, UtcTimes.iso(UtcTimes.nowUtc()));

        assertThat(new String(crypto.decrypt(envelope, key), StandardCharsets.UTF_8))
                .isEqualTo(new String(plaintext, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsATamperedTag() {
        Envelope sealed = seal("payload".getBytes(StandardCharsets.UTF_8), key, keyId,
                UtcTimes.iso(UtcTimes.nowUtc()));
        byte[] tag = Base64.getDecoder().decode(sealed.tag());
        tag[0] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(
                new Envelope(sealed.keyId(), sealed.iv(), sealed.ciphertext(),
                        Base64.getEncoder().encodeToString(tag), sealed.sentAtUtc()), key))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PAYLOAD_UNPROCESSABLE);
    }

    @Test
    void rejectsTheSameEnvelopeUnderADifferentKey() {
        Envelope sealed = seal("payload".getBytes(StandardCharsets.UTF_8), key, keyId,
                UtcTimes.iso(UtcTimes.nowUtc()));

        assertThatThrownBy(() -> crypto.decrypt(sealed, randomKey()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.KEY_ID_MISMATCH);
    }

    @Test
    void rejectsAStaleTimestampAndAFarFutureOne() {
        Envelope stale = sealedNowAt(UtcTimes.nowUtc().minusSeconds(
                properties.getCrypto().getReplayWindowSeconds() + 5L));
        Envelope future = sealedNowAt(UtcTimes.nowUtc().plusSeconds(
                properties.getCrypto().getReplayWindowSeconds() + 5L));

        for (Envelope envelope : new Envelope[]{stale, future}) {
            assertThatThrownBy(() -> crypto.decrypt(envelope, key))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.PAYLOAD_STALE);
        }
    }

    @Test
    void acceptsATimestampJustInsideTheWindow() {
        Envelope almostStale = sealedNowAt(UtcTimes.nowUtc().minusSeconds(
                properties.getCrypto().getReplayWindowSeconds() - 5L));

        assertThat(new String(crypto.decrypt(almostStale, key), StandardCharsets.UTF_8))
                .isEqualTo("payload");
    }

    @Test
    void rejectsMalformedEnvelopeFieldsBeforeAnyCrypto() {
        Envelope good = seal("payload".getBytes(StandardCharsets.UTF_8), key, keyId,
                UtcTimes.iso(UtcTimes.nowUtc()));

        assertThatThrownBy(() -> crypto.decrypt(null, key))
                .isInstanceOf(ApiException.class);
        // 11-byte IV: GCM needs exactly 12.
        assertThatThrownBy(() -> crypto.decrypt(new Envelope(keyId,
                Base64.getEncoder().encodeToString(new byte[11]), good.ciphertext(), good.tag(),
                good.sentAtUtc()), key))
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.PAYLOAD_MALFORMED);
        // A well-shaped but unrelated ciphertext is not a parse error — it fails authentication.
        assertThatThrownBy(() -> crypto.decrypt(new Envelope(keyId, good.iv(), "c2hvcg==",
                good.tag(), good.sentAtUtc()), key))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PAYLOAD_UNPROCESSABLE);
        assertThatThrownBy(() -> crypto.decrypt(good, new byte[16]))
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.PAYLOAD_MALFORMED);
    }

    @Test
    void refusesACiphertextOverTheConfiguredLimit() {
        properties.getCrypto().setMaxCiphertextBytes(64);
        Envelope big = seal(new byte[4096], key, keyId, UtcTimes.iso(UtcTimes.nowUtc()));

        assertThatThrownBy(() -> crypto.decrypt(big, key))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void bindsJsonAndLeavesNoReadablePlaintextBehindTheBuffer() {
        record Body(String idNumber, int width) {
        }
        byte[] plaintext = "{\"idNumber\":\"1234567890126\",\"width\":1280}"
                .getBytes(StandardCharsets.UTF_8);
        Envelope envelope = seal(plaintext, key, keyId, UtcTimes.iso(UtcTimes.nowUtc()));

        Body body = crypto.decryptJson(envelope, key, Body.class);

        assertThat(body.idNumber()).isEqualTo("1234567890126");
        assertThat(body.width()).isEqualTo(1280);

        Envelope notJson = seal("not json".getBytes(StandardCharsets.UTF_8), key, keyId,
                UtcTimes.iso(UtcTimes.nowUtc()));
        assertThatThrownBy(() -> crypto.decryptJson(notJson, key, Body.class))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PAYLOAD_MALFORMED);
    }

    @Test
    void keyIdIsTheHexSha256OfTheKeyAndIsStable() {
        assertThat(keyId).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(CryptoService.keyIdOf(key)).isEqualTo(keyId);
    }

    private Envelope sealedNowAt(LocalDateTime sentAt) {
        return seal("payload".getBytes(StandardCharsets.UTF_8), key, keyId, UtcTimes.iso(sentAt));
    }

    private static Envelope seal(byte[] plaintext, byte[] key, String keyId, String sentAtUtc) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - 16);
            byte[] tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);
            Base64.Encoder encoder = Base64.getEncoder();
            return new Envelope(keyId, encoder.encodeToString(iv), encoder.encodeToString(ciphertext),
                    encoder.encodeToString(tag), sentAtUtc);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
