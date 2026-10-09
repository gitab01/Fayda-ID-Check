package id.fayda.verification.infra.inference;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import id.fayda.verification.infra.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * HTTP binding to the inference service (CONTRACT §2, base {@code /v1}), using Spring's
 * {@link RestClient} with a 5 s connect and 10 s read timeout.
 *
 * <p>The outbound body is built from {@link InferenceRequest} only — a request-scoped id, the
 * in-memory frames, the challenge seed and the expected action order. It has no field that could
 * carry a user id, phone number or national id, so leaking identity to the inference peer is
 * structurally impossible rather than merely avoided.</p>
 *
 * <p>Every failure path — connect/read timeout, non-2xx, an unparseable body, a score outside
 * 0..1, a missing model version — raises {@link InferenceUnavailableException}. No default score
 * is ever invented.</p>
 */
@Service
public class RestClientInferenceClient implements InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientInferenceClient.class);
    static final String VERIFY_PATH = "/v1/verify";

    private final RestClient restClient;
    private final int maxFrames;

    public RestClientInferenceClient(AppProperties properties, RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl(properties.getInference().getBaseUrl())
                .requestFactory(requestFactory(properties))
                .build();
        this.maxFrames = properties.getCrypto().getMaxFrames();
    }

    /** 5 s connect / 10 s read by default (CONTRACT §2 keeps the verification path bounded). */
    static SimpleClientHttpRequestFactory requestFactory(AppProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getInference().getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getInference().getReadTimeoutMs());
        return requestFactory;
    }

    @Override
    public VerifyScores verify(VerifyRequest request) {
        if (request.framesBase64().size() > maxFrames) {
            throw new InferenceUnavailableException(request.requestId(),
                    "inference request exceeds the " + maxFrames + " frame limit", null);
        }
        InferenceRequest body = new InferenceRequest(request.requestId(),
                request.framesBase64(), request.challengeSeed(), request.expectedActions(),
                request.faceBox());
        try {
            InferenceResponse response = restClient.post()
                    .uri(VERIFY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", request.requestId())
                    .body(body)
                    .retrieve()
                    .body(InferenceResponse.class);
            return toScores(request.requestId(), response);
        } catch (RestClientException e) {
            // Log the correlation id and the failure class only: never the frames, never a subject.
            log.warn("inference call failed requestId={} reason={}", request.requestId(),
                    e.getClass().getSimpleName());
            throw new InferenceUnavailableException(request.requestId(),
                    "the inference service did not return usable scores", e);
        }
    }

    private VerifyScores toScores(String requestId, InferenceResponse response) {
        if (response == null) {
            throw new InferenceUnavailableException(requestId,
                    "inference returned an empty body", null);
        }
        if (response.attackScore() == null || response.similarity() == null) {
            throw new InferenceUnavailableException(requestId,
                    "inference returned incomplete scores", null);
        }
        if (isOutsideUnitInterval(response.attackScore())
                || isOutsideUnitInterval(response.similarity())) {
            throw new InferenceUnavailableException(requestId,
                    "inference returned scores outside 0..1", null);
        }
        if (isBlank(response.livenessModelVersion()) || isBlank(response.embeddingModelVersion())) {
            // decision_records.model_ver columns are NOT NULL: an unversioned score is unusable.
            throw new InferenceUnavailableException(requestId,
                    "inference returned scores without model versions", null);
        }
        return new VerifyScores(response.attackScore(), response.similarity(),
                response.livenessModelVersion(), response.embeddingModelVersion());
    }

    private static boolean isOutsideUnitInterval(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Outbound body — the field list is the guarantee that no identity crosses the boundary. */
    record InferenceRequest(String requestId, List<String> frames, String challengeSeed,
                            List<String> expectedActions, List<Integer> faceBox) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InferenceResponse(String requestId, BigDecimal attackScore, BigDecimal similarity,
                             String livenessModelVersion, String embeddingModelVersion) {
    }
}
