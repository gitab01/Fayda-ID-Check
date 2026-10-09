package id.fayda.verification.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import id.fayda.verification.VerificationServiceApplication;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.infra.inference.InferenceClient;
import id.fayda.verification.infra.inference.InferenceUnavailableException;
import id.fayda.verification.service.ThresholdProfileProvider;
import id.fayda.verification.service.UtcTimes;
import id.fayda.verification.service.crypto.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract end to end over the real HTTP stack: scopes, the envelope, the lifecycle, the
 * decision, and the failure paths a mobile client actually hits (stale replay, wrong state,
 * inference down). Inference is the only mocked boundary — everything else is shipped wiring
 * running on H2 with the profile seeded from {@code threshold-defaults.yml}.
 */
@SpringBootTest(classes = VerificationServiceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "verification.rate-limit.enabled=false")
class AttemptFlowIntegrationTest {

    /** 13 digits whose mod-11 check digit agrees, per DocumentValidator. */
    private static final String ID_NUMBER = "1234567890126";
    private static final InferenceClient.VerifyScores GOOD_SCORES =
            new InferenceClient.VerifyScores(new BigDecimal("0.02"), new BigDecimal("0.95"),
                    "liveness-cnn-1.3.0", "arcface-r18-1.1.0");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppProperties properties;
    @MockitoBean InferenceClient inferenceClient;

    @Test
    void happyPathStartsCapturesDecidesAndReadsBack() throws Exception {
        when(inferenceClient.verify(any())).thenReturn(GOOD_SCORES);

        byte[] key = randomKey();
        String subject = token(1001L, "subject");
        Started started = startAttempt(subject, key, "NATIONAL_ID");

        submitDocument(subject, key, started);
        submitSelfie(subject, key, started);

        mvc.perform(bearer(verify(started.attemptId()), subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.reasonCode").value("COMPOSITE_ABOVE_PASS"))
                .andExpect(jsonPath("$.thresholdVersion").value(bundledVersion()))
                .andExpect(jsonPath("$.scores.document").exists())
                .andExpect(jsonPath("$.guidance").isNotEmpty());

        mvc.perform(get("/api/v1/attempts/" + started.attemptId())
                        .header("Authorization", "Bearer " + subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.decision.decision").value("PASS"))
                .andExpect(jsonPath("$.decision.attemptId").value(started.attemptId()));
    }

    @Test
    void aTokenlessCallIsRejectedWithTheContractErrorBody() throws Exception {
        mvc.perform(post("/api/v1/attempts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"NATIONAL_ID\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void aReviewerReadsAcrossSubjectsButNeverSubmitsOrDecides() throws Exception {
        byte[] key = randomKey();
        String subject = token(2002L, "subject");
        Started started = startAttempt(subject, key, "NATIONAL_ID");
        String reviewer = token(2L, "reviewer");

        // A reviewer scope may legitimately cross subjects, so the read is allowed.
        mvc.perform(get("/api/v1/attempts/" + started.attemptId())
                        .header("Authorization", "Bearer " + reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));

        mvc.perform(post("/api/v1/attempts/" + started.attemptId() + "/verify")
                        .header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_SCOPE"));
    }

    @Test
    void anotherSubjectCannotSeeSomeoneElsesAttempt() throws Exception {
        byte[] key = randomKey();
        Started started = startAttempt(token(3003L, "subject"), key, "NATIONAL_ID");

        mvc.perform(get("/api/v1/attempts/" + started.attemptId())
                        .header("Authorization", "Bearer " + token(9009L, "subject")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_VISIBLE"));
    }

    @Test
    void aDecisionNeedsACapturedAttemptAndAWellFormedEnvelope() throws Exception {
        byte[] key = randomKey();
        String subject = token(4004L, "subject");
        Started started = startAttempt(subject, key, "NATIONAL_ID");

        mvc.perform(bearer(verify(started.attemptId()), subject))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_CAPTURED"));

        // Sealed under a different key: the envelope is not this attempt's, and nothing is parsed.
        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(randomKey(), "{\"image\":null}")), subject))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KEY_ID_MISMATCH"));

        // An envelope missing its fields fails validation before any crypto runs.
        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/document")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyId\":\"x\"}"), subject))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anEnvelopeOutsideTheReplayWindowIsRejectedAsStale() throws Exception {
        byte[] key = randomKey();
        String subject = token(5005L, "subject");
        Started started = startAttempt(subject, key, "NATIONAL_ID");
        String stale = envelope(key, documentBody(), UtcTimes.iso(
                UtcTimes.nowUtc().minusSeconds(properties.getCrypto().getReplayWindowSeconds() + 30)));

        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/document")
                        .contentType(MediaType.APPLICATION_JSON).content(stale), subject))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYLOAD_STALE"));
    }

    @Test
    void inferenceDownParksTheAttemptAndARetryRecoversToADecision() throws Exception {
        when(inferenceClient.verify(any()))
                .thenThrow(new InferenceUnavailableException("inf-1", "peer is down", null))
                .thenReturn(GOOD_SCORES);

        byte[] key = randomKey();
        String subject = token(6006L, "subject");
        Started started = startAttempt(subject, key, "NATIONAL_ID");
        submitDocument(subject, key, started);
        submitSelfie(subject, key, started);

        mvc.perform(bearer(verify(started.attemptId()), subject))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INFERENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));

        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/retry"), subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.attemptNo").value(2));

        // The captured frames were released with the failure; the stored document metadata survived.
        submitSelfie(subject, key, started);
        mvc.perform(bearer(verify(started.attemptId()), subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.compositeScore").exists());
    }

    // ------------------------------------------------------------------ helpers ----------------

    /** The pieces of a started attempt the rest of a flow needs. */
    private record Started(long attemptId, List<String> actions) {
    }

    private Started startAttempt(String bearer, byte[] key, String documentType) throws Exception {
        MvcResult result = mvc.perform(bearer(
                        post("/api/v1/attempts")
                                .header(AttemptController.ATTEMPT_KEY_HEADER,
                                        Base64.getEncoder().encodeToString(key))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "documentType", documentType,
                                        "deviceInfo", "Pixel 6a / Android 14 / v1.2.0"))),
                        bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.uploadKey.algorithm").value("AES-256-GCM"))
                .andExpect(jsonPath("$.uploadKey.keyId").value(CryptoService.keyIdOf(key)))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<String> actions = new java.util.ArrayList<>();
        body.path("challenge").path("actions").forEach(node -> actions.add(node.asText()));
        org.assertj.core.api.Assertions.assertThat(actions).hasSize(3);
        return new Started(body.path("attemptId").asLong(), List.copyOf(actions));
    }

    private void submitDocument(String bearer, byte[] key, Started started) throws Exception {
        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(key, documentBody())), bearer))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentAccepted").value(true));
    }

    private void submitSelfie(String bearer, byte[] key, Started started) throws Exception {
        mvc.perform(bearer(post("/api/v1/attempts/" + started.attemptId() + "/selfie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(key, selfieBody(started.actions()))), bearer))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    private String documentBody() throws Exception {
        return json.writeValueAsString(Map.of(
                "image", Map.of("bytes64", base64(jpeg()), "width", 1280, "height", 720,
                        "mimeType", "image/jpeg"),
                "ocr", Map.of("idNumber", ID_NUMBER, "fullName", "Abel Bekele",
                        "issuingRegion", "Addis Ababa", "expiryDate", "2030-04-01"),
                "quality", Map.of("blurScore", 212.5, "glareRatio", 0.01, "cornersFound", true)));
    }

    private String selfieBody(List<String> actions) throws Exception {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> frames = actions.stream()
                .map(action -> Map.<String, Object>of("bytes64", base64(jpeg()), "action", action,
                        "capturedAtMs", now))
                .toList();
        return json.writeValueAsString(Map.of(
                "frames", frames,
                "executedActions", actions,
                "quality", Map.of("blurScore", 180.0, "faceBox", List.of(412, 180, 240, 300))));
    }

    private String envelope(byte[] key, String plaintext) throws Exception {
        return envelope(key, plaintext, UtcTimes.iso(UtcTimes.nowUtc()));
    }

    private String envelope(byte[] key, String plaintext, String sentAtUtc) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getEncoder();
        return json.writeValueAsString(Map.of(
                "keyId", CryptoService.keyIdOf(key),
                "iv", encoder.encodeToString(iv),
                "ciphertext", encoder.encodeToString(
                        Arrays.copyOfRange(sealed, 0, sealed.length - 16)),
                "tag", encoder.encodeToString(
                        Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length)),
                "sentAtUtc", sentAtUtc));
    }

    private static MockHttpServletRequestBuilder verify(long attemptId) {
        return post("/api/v1/attempts/" + attemptId + "/verify")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder,
                                                        String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String token(long userId, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .issuer(properties.getSecurity().getIssuer())
                .claim("scope", scope)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(
                properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String bundledVersion() {
        return new ThresholdProfileProvider(null).bundledDefault().version();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** The smallest thing that passes the magic-byte check: a JPEG header on random bytes. */
    private static byte[] jpeg() {
        byte[] bytes = new byte[256];
        new SecureRandom().nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
