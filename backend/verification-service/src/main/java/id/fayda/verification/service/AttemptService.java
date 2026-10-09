package id.fayda.verification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import id.fayda.verification.api.dto.AttemptStatusResponse;
import id.fayda.verification.api.dto.DecisionResponse;
import id.fayda.verification.api.dto.DocumentAcceptedResponse;
import id.fayda.verification.api.dto.DocumentPayload;
import id.fayda.verification.api.dto.RetryResponse;
import id.fayda.verification.api.dto.SelfieAcceptedResponse;
import id.fayda.verification.api.dto.SelfiePayload;
import id.fayda.verification.api.dto.StartAttemptRequest;
import id.fayda.verification.api.dto.StartAttemptResponse;
import id.fayda.verification.domain.AttemptStatus;
import id.fayda.verification.domain.ChallengeAction;
import id.fayda.verification.domain.entity.DecisionRecord;
import id.fayda.verification.domain.entity.DocumentMetadata;
import id.fayda.verification.domain.entity.UserAccount;
import id.fayda.verification.domain.entity.VerificationAttempt;
import id.fayda.verification.domain.state.AttemptStateMachine;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.infra.inference.InferenceClient;
import id.fayda.verification.infra.inference.InferenceUnavailableException;
import id.fayda.verification.infra.repo.DecisionRecordRepository;
import id.fayda.verification.infra.repo.DocumentMetadataRepository;
import id.fayda.verification.infra.repo.UserRepository;
import id.fayda.verification.infra.repo.VerificationAttemptRepository;
import id.fayda.verification.service.crypto.AttemptKeyRegistry;
import id.fayda.verification.service.crypto.CryptoService;
import id.fayda.verification.service.crypto.Envelope;
import id.fayda.verification.service.crypto.ImageDecoder;
import id.fayda.verification.service.decision.DecisionInput;
import id.fayda.verification.service.decision.DecisionOutcome;
import id.fayda.verification.service.decision.DecisionService;
import id.fayda.verification.service.decision.ThresholdProfile;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The attempt lifecycle, and the only place that changes attempt state.
 *
 * <p>Every stage is bounded: the row carries {@code stage_deadline_utc} for the state it is in,
 * each change is asserted against {@link AttemptStateMachine}, and an attempt that has blown its
 * deadline is expired on the spot by whoever touches it — independently of the scheduled sweeper.</p>
 *
 * <p>The verify path runs as two short transactions with the inference call in between, so a
 * 10-second inference timeout never parks a database connection or an attempt row lock.</p>
 */
@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
    private static final String AES_ALGORITHM = "AES-256-GCM";
    private static final int MIN_SELFIE_FRAMES = 3;
    private static final int PAYLOAD_KEY_BYTES = 32;

    private final VerificationAttemptRepository attemptRepository;
    private final DecisionRecordRepository decisionRepository;
    private final DocumentMetadataRepository documentRepository;
    private final UserRepository userRepository;
    private final AttemptStateMachine stateMachine;
    private final ChallengeService challengeService;
    private final CryptoService cryptoService;
    private final AttemptKeyRegistry keyRegistry;
    private final ImageDecoder imageDecoder;
    private final HashService hashService;
    private final DocumentValidator documentValidator;
    private final PendingCaptureStore captureStore;
    private final AttemptContextStore contextStore;
    private final DecisionService decisionService;
    private final ThresholdProfileProvider thresholdProfiles;
    private final AuditService auditService;
    private final InferenceClient inferenceClient;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties properties;

    public AttemptService(VerificationAttemptRepository attemptRepository,
                          DecisionRecordRepository decisionRepository,
                          DocumentMetadataRepository documentRepository,
                          UserRepository userRepository,
                          AttemptStateMachine stateMachine,
                          ChallengeService challengeService,
                          CryptoService cryptoService,
                          AttemptKeyRegistry keyRegistry,
                          ImageDecoder imageDecoder,
                          HashService hashService,
                          DocumentValidator documentValidator,
                          PendingCaptureStore captureStore,
                          AttemptContextStore contextStore,
                          DecisionService decisionService,
                          ThresholdProfileProvider thresholdProfiles,
                          AuditService auditService,
                          InferenceClient inferenceClient,
                          TransactionTemplate transactionTemplate,
                          AppProperties properties) {
        this.attemptRepository = attemptRepository;
        this.decisionRepository = decisionRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.stateMachine = stateMachine;
        this.challengeService = challengeService;
        this.cryptoService = cryptoService;
        this.keyRegistry = keyRegistry;
        this.imageDecoder = imageDecoder;
        this.hashService = hashService;
        this.documentValidator = documentValidator;
        this.captureStore = captureStore;
        this.contextStore = contextStore;
        this.decisionService = decisionService;
        this.thresholdProfiles = thresholdProfiles;
        this.auditService = auditService;
        this.inferenceClient = inferenceClient;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- POST /attempts ---------

    public StartAttemptResponse start(Actor actor, StartAttemptRequest request, byte[] payloadKey) {
        UserAccount subject = requireSubject(actor);
        requirePayloadKey(payloadKey);
        String documentType = normalizeDocumentType(request.documentType());

        ThresholdProfile profile = thresholdProfiles.activeProfile();
        int attemptNo = attemptRepository.findHighestAttemptNo(subject.getId()) + 1;
        String seed = challengeService.newSeed();
        List<ChallengeAction> actions = challengeService.derive(seed);
        LocalDateTime now = UtcTimes.nowUtc();
        LocalDateTime deadline = now.plusSeconds(properties.getDeadline().getCreatedSeconds());

        VerificationAttempt attempt = new VerificationAttempt();
        attempt.setUserId(subject.getId());
        attempt.setStatus(AttemptStatus.CREATED);
        attempt.setChallengeSeed(seed);
        attempt.setChallengeActions(challengeService.toStoredForm(actions));
        attempt.setDeviceInfo(truncate(request.deviceInfo(), 256));
        // The key itself is never persisted: only sha256(keyId), exactly as the column documents.
        attempt.setRequestKeyHash(hashService.sha256Hex(CryptoService.keyIdOf(payloadKey)));
        attempt.setAttemptNo(attemptNo);
        attempt.setStartedAtUtc(now);
        attempt.setStageDeadlineUtc(deadline);
        VerificationAttempt saved = transactionTemplate.execute(status -> attemptRepository.save(attempt));

        long attemptId = saved.getId();
        keyRegistry.register(attemptId, payloadKey, stageTtl(AttemptStatus.CREATED));
        contextStore.declareDocumentType(attemptId, documentType,
                stageTtl(AttemptStatus.CREATED).plusSeconds(
                        properties.getDeadline().getCapturedSeconds()));
        auditService.attemptStarted(actor, attemptId);
        log.info("attempt started attemptId={} attemptNo={} status=CREATED", attemptId, attemptNo);

        return new StartAttemptResponse(attemptId, AttemptStatus.CREATED.name(),
                new StartAttemptResponse.Challenge(seed, actions.stream().map(Enum::name).toList()),
                new StartAttemptResponse.UploadKey(CryptoService.keyIdOf(payloadKey), AES_ALGORITHM,
                        UtcTimes.iso(deadline)),
                UtcTimes.iso(deadline), attemptNo,
                Math.max(0, profile.maxAttempts() - attemptNo));
    }

    // ------------------------------------------------------- POST /attempts/{id}/document -----

    public DocumentAcceptedResponse submitDocument(Actor actor, long attemptId, Envelope envelope) {
        VerificationAttempt attempt = loadActionable(actor, attemptId);
        requireState(attempt, AttemptStatus.CREATED, ErrorCode.DOCUMENT_ALREADY_SUBMITTED,
                "a document payload is only accepted while the attempt is CREATED");

        byte[] key = keyRegistry.require(attemptId);
        DocumentPayload payload = cryptoService.decryptJson(envelope, key, DocumentPayload.class);
        boolean accepted = false;
        try {
            if (payload.image() == null || payload.ocr() == null) {
                throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                        "the document payload needs both the image and the ocr block");
            }
            String documentType = contextStore.requireDocumentType(attemptId);
            documentValidator.requireReadable(documentType, payload.ocr().idNumber());
            requireQualityGate(payload.quality());

            // Decoded into a heap buffer, inspected, then erased. Nothing here reaches the disk.
            ImageDecoder.DecodedImage image = imageDecoder.decode(payload.image().bytes64(),
                    payload.image().width(), payload.image().height(), payload.image().mimeType());
            try {
                DocumentValidator.Validation validation = documentValidator.validate(documentType,
                        payload.ocr().idNumber(), payload.ocr().expiryDate());
                HashService.SaltedHash idHash = hashService.hash(payload.ocr().idNumber());
                BigDecimal ocrConfidence = storedConfidence(payload.quality());

                transactionTemplate.executeWithoutResult(status -> {
                    DocumentMetadata metadata = documentRepository.findByAttemptId(attemptId)
                            .orElseGet(DocumentMetadata::new);
                    metadata.setAttemptId(attemptId);
                    metadata.setDocumentType(truncate(documentType, 32));
                    metadata.setIdNumberHash(idHash.hash());
                    metadata.setHashSalt(idHash.salt());
                    metadata.setIssuingRegion(truncate(payload.ocr().issuingRegion(), 64));
                    metadata.setExpiryDate(validation.expiryDate());
                    metadata.setMrzValid(validation.mrzValid());
                    metadata.setChecksumValid(validation.checksumValid());
                    metadata.setOcrConfidence(ocrConfidence);
                    metadata.setCapturedAtUtc(UtcTimes.nowUtc());
                    documentRepository.save(metadata);
                });
            } finally {
                image.destroy();
            }
            accepted = true;
            return new DocumentAcceptedResponse(attemptId, attempt.getStatus().name(), true);
        } finally {
            auditService.payloadReceived(actor, attemptId, "document", accepted);
        }
    }

    // --------------------------------------------------------- POST /attempts/{id}/selfie -----
    // CREATED -> CAPTURED

    public SelfieAcceptedResponse submitSelfie(Actor actor, long attemptId, Envelope envelope) {
        VerificationAttempt attempt = loadActionable(actor, attemptId);
        requireState(attempt, AttemptStatus.CREATED, ErrorCode.ATTEMPT_NOT_CAPTURED,
                "a selfie payload is only accepted while the attempt is CREATED");

        byte[] key = keyRegistry.require(attemptId);
        SelfiePayload payload = cryptoService.decryptJson(envelope, key, SelfiePayload.class);
        boolean accepted = false;
        try {
            List<SelfiePayload.Frame> frames = payload.frames();
            int frameCount = frames == null ? 0 : frames.size();
            if (frameCount < MIN_SELFIE_FRAMES) {
                throw ApiException.of(ErrorCode.INSUFFICIENT_FRAMES,
                        "at least %d frames are required (got %d)", MIN_SELFIE_FRAMES, frameCount);
            }
            if (frameCount > properties.getCrypto().getMaxFrames()) {
                throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE,
                        "no more than %d frames may be submitted",
                        properties.getCrypto().getMaxFrames());
            }
            // The authority is the sequence re-derived from the seed, never the stored copy.
            challengeService.requireSequenceMatches(attempt.getChallengeSeed(),
                    payload.executedActions());

            for (SelfiePayload.Frame frame : frames) {
                ImageDecoder.DecodedImage decoded = imageDecoder.decodeFrame(frame.bytes64());
                decoded.destroy();
            }

            captureStore.store(attemptId,
                    frames.stream().map(SelfiePayload.Frame::bytes64).toList(),
                    payload.executedActions(), payload.faceBoxOrEmpty(),
                    stageTtl(AttemptStatus.CAPTURED));

            transactionTemplate.executeWithoutResult(status -> {
                VerificationAttempt fresh = attemptRepository.findById(attemptId).orElse(attempt);
                move(fresh, AttemptStatus.CAPTURED);
                attemptRepository.save(fresh);
            });
            attempt.setStatus(AttemptStatus.CAPTURED);
            accepted = true;
            return new SelfieAcceptedResponse(attemptId, AttemptStatus.CAPTURED.name(),
                    frameCount, true);
        } finally {
            auditService.payloadReceived(actor, attemptId, "selfie", accepted);
        }
    }

    // --------------------------------------------------------- POST /attempts/{id}/verify -----
    // CAPTURED -> INFERRING -> DECIDED, or INFERRING -> FAILED_RETRYABLE + HTTP 503

    public DecisionResponse verify(Actor actor, long attemptId) {
        VerificationAttempt attempt = loadActionable(actor, attemptId);
        switch (attempt.getStatus()) {
            case DECIDED -> throw ApiException.of(ErrorCode.ATTEMPT_ALREADY_DECIDED,
                    "attempt %d already carries a decision", attemptId);
            case CAPTURED -> {
                // the only state a decision may be computed from
            }
            default -> throw ApiException.of(ErrorCode.ATTEMPT_NOT_CAPTURED,
                    "attempt %d is %s; a decision requires a CAPTURED attempt", attemptId,
                    attempt.getStatus());
        }
        DocumentMetadata metadata = documentRepository.findByAttemptId(attemptId)
                .orElseThrow(() -> ApiException.of(ErrorCode.DOCUMENT_INVALID,
                        "attempt %d has no document metadata; submit the document first", attemptId));
        PendingCaptureStore.PendingCapture capture = captureStore.require(attemptId);

        transactionTemplate.executeWithoutResult(status -> {
            VerificationAttempt fresh = attemptRepository.findById(attemptId).orElse(attempt);
            move(fresh, AttemptStatus.INFERRING);
            attemptRepository.save(fresh);
        });

        int attemptNo = attempt.getAttemptNo();
        String inferenceRequestId = UUID.randomUUID().toString();
        List<String> expectedActions = challengeService.derivedNames(attempt.getChallengeSeed());
        InferenceClient.VerifyScores scores;
        try {
            scores = inferenceClient.verify(new InferenceClient.VerifyRequest(inferenceRequestId,
                    capture.framesBase64(), attempt.getChallengeSeed(), expectedActions,
                    capture.faceBox()));
            auditService.inferenceCalled(actor, attemptId, inferenceRequestId, true,
                    "liveness=" + scores.livenessModelVersion()
                            + " embedding=" + scores.embeddingModelVersion());
        } catch (InferenceUnavailableException | IllegalArgumentException e) {
            auditService.inferenceCalled(actor, attemptId, inferenceRequestId, false,
                    e.getClass().getSimpleName());
            transactionTemplate.executeWithoutResult(status -> {
                VerificationAttempt fresh = attemptRepository.findById(attemptId)
                        .orElse(attempt);
                move(fresh, AttemptStatus.FAILED_RETRYABLE);
                attemptRepository.save(fresh);
            });
            captureStore.release(attemptId);
            log.warn("inference failed for attemptId={} requestId={} type={}", attemptId,
                    inferenceRequestId, e.getClass().getSimpleName());
            if (e instanceof InferenceUnavailableException unavailable) {
                throw unavailable;
            }
            throw new InferenceUnavailableException(inferenceRequestId,
                    "the inference service returned nothing usable", e);
        }

        BigDecimal documentScore = documentScoreOf(metadata);
        DecisionOutcome outcome = decisionService.decide(
                new DecisionInput(scores.attackScore(), scores.similarity(), documentScore,
                        metadata.isMrzValid(), metadata.isChecksumValid(), attemptNo),
                thresholdProfiles.activeProfile());

        LocalDateTime decidedAt = UtcTimes.nowUtc();
        transactionTemplate.executeWithoutResult(status -> {
            VerificationAttempt fresh = attemptRepository.findById(attemptId).orElse(attempt);
            move(fresh, AttemptStatus.DECIDED);
            fresh.setCompletedAtUtc(decidedAt);
            attemptRepository.save(fresh);
            decisionRepository.save(toRecord(attemptId, outcome, scores, documentScore, decidedAt));
        });

        auditService.decisionMade(actor, attemptId, outcome.decision().name(), outcome.reasonCode(),
                outcome.thresholdVersion());
        // The decrypted material must not outlive the attempt it belongs to.
        captureStore.release(attemptId);
        keyRegistry.release(attemptId);
        contextStore.release(attemptId);
        log.info("decision made attemptId={} decision={} reason={} composite={} profile={}",
                attemptId, outcome.decision(), outcome.reasonCode(), outcome.compositeScore(),
                outcome.thresholdVersion());

        return new DecisionResponse(attemptId, outcome.decision().name(), outcome.compositeScore(),
                new DecisionResponse.Scores(scores.attackScore(), scores.similarity(), documentScore),
                outcome.reasonCode(), outcome.guidance(), outcome.thresholdVersion(),
                UtcTimes.iso(decidedAt));
    }

    // ------------------------------------------------------------ GET /attempts/{id} ----------
    // Always writes a RECORD_READ audit row.

    public AttemptStatusResponse status(Actor actor, long attemptId) {
        VerificationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ATTEMPT_NOT_FOUND,
                        "no attempt %d", attemptId));
        requireVisible(actor, attempt);
        auditService.recordRead(actor, attemptId);
        DecisionResponse decision = decisionRepository.findByAttemptId(attemptId)
                .map(record -> DecisionResponse.from(record, UtcTimes.iso(record.getDecidedAtUtc())))
                .orElse(null);
        return new AttemptStatusResponse(attempt.getId(), attempt.getStatus().name(),
                attempt.getAttemptNo(), UtcTimes.iso(attempt.getStageDeadlineUtc()),
                UtcTimes.iso(attempt.getCompletedAtUtc()), decision);
    }

    // ------------------------------------------------------- POST /attempts/{id}/retry --------

    public RetryResponse retry(Actor actor, long attemptId) {
        VerificationAttempt attempt = loadActionable(actor, attemptId);
        if (attempt.getStatus() != AttemptStatus.FAILED_RETRYABLE) {
            throw ApiException.of(ErrorCode.RETRY_NOT_ALLOWED,
                    "attempt %d is %s; only a FAILED_RETRYABLE attempt can be retried", attemptId,
                    attempt.getStatus());
        }
        ThresholdProfile profile = thresholdProfiles.activeProfile();
        int nextAttemptNo = attempt.getAttemptNo() + 1;
        byte[] key = keyRegistry.require(attemptId);
        LocalDateTime deadline = UtcTimes.nowUtc().plusSeconds(
                properties.getDeadline().getCreatedSeconds());

        transactionTemplate.executeWithoutResult(status -> {
            VerificationAttempt fresh = attemptRepository.findById(attemptId).orElse(attempt);
            // FAILED_RETRYABLE -> CREATED; past the cap the §4 attempt-cap floor forces REVIEW.
            move(fresh, AttemptStatus.CREATED);
            fresh.setAttemptNo(nextAttemptNo);
            fresh.setCompletedAtUtc(null);
            fresh.setStageDeadlineUtc(deadline);
            attemptRepository.save(fresh);
        });
        keyRegistry.register(attemptId, key, stageTtl(AttemptStatus.CREATED));
        auditService.attemptRetried(actor, attemptId, nextAttemptNo);
        log.info("attempt retried attemptId={} attemptNo={}", attemptId, nextAttemptNo);

        return new RetryResponse(attemptId, AttemptStatus.CREATED.name(), nextAttemptNo,
                Math.max(0, profile.maxAttempts() - nextAttemptNo), UtcTimes.iso(deadline));
    }

    // ------------------------------------------------------ expiry, called by the sweeper -----

    public boolean expire(long attemptId, Actor actor) {
        Boolean didExpire = transactionTemplate.execute(status -> {
            Optional<VerificationAttempt> found = attemptRepository.findById(attemptId);
            if (found.isEmpty()) {
                return false;
            }
            VerificationAttempt attempt = found.get();
            if (!stateMachine.canTransition(attempt.getStatus(), AttemptStatus.EXPIRED)) {
                return false;
            }
            stateMachine.assertCanTransition(attempt.getStatus(), AttemptStatus.EXPIRED);
            attempt.setStatus(AttemptStatus.EXPIRED);
            attempt.setCompletedAtUtc(UtcTimes.nowUtc());
            attemptRepository.save(attempt);
            return true;
        });
        if (!Boolean.TRUE.equals(didExpire)) {
            return false;
        }
        captureStore.release(attemptId);
        keyRegistry.release(attemptId);
        contextStore.release(attemptId);
        auditService.attemptExpired(actor, attemptId);
        log.info("attempt expired attemptId={}", attemptId);
        return true;
    }

    // ------------------------------------------------------------------- internals -----------

    /** Loads an attempt for a state-changing call: 404, ownership, then deadline enforcement. */
    private VerificationAttempt loadActionable(Actor actor, long attemptId) {
        VerificationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ATTEMPT_NOT_FOUND,
                        "no attempt %d", attemptId));
        requireVisible(actor, attempt);
        if (isStalled(attempt)) {
            expire(attemptId, actor);
            throw ApiException.of(ErrorCode.ATTEMPT_EXPIRED,
                    "attempt %d passed its stage deadline and is now EXPIRED", attemptId);
        }
        return attempt;
    }

    private void requireVisible(Actor actor, VerificationAttempt attempt) {
        boolean privileged = actor.isSystem() || actor.isReviewer();
        if (!privileged && !attempt.getUserId().equals(actor.actorId())) {
            throw ApiException.of(ErrorCode.ATTEMPT_NOT_VISIBLE,
                    "attempt %d belongs to another subject", attempt.getId());
        }
    }

    private void requireState(VerificationAttempt attempt, AttemptStatus expected,
                              ErrorCode otherwise, String why) {
        if (attempt.getStatus() == expected) {
            return;
        }
        ErrorCode code = switch (attempt.getStatus()) {
            case EXPIRED -> ErrorCode.ATTEMPT_EXPIRED;
            case DECIDED -> ErrorCode.ATTEMPT_ALREADY_DECIDED;
            default -> otherwise;
        };
        throw ApiException.of(code, "%s (attempt %d is %s)", why, attempt.getId(),
                attempt.getStatus());
    }

    /** Applies the transition through the state machine and re-arms the stage deadline. */
    private void move(VerificationAttempt attempt, AttemptStatus target) {
        stateMachine.assertCanTransition(attempt.getStatus(), target);
        attempt.setStatus(target);
        if (target != AttemptStatus.DECIDED && target != AttemptStatus.EXPIRED) {
            attempt.setStageDeadlineUtc(UtcTimes.nowUtc().plusSeconds(deadlineSeconds(target)));
        }
    }

    private boolean isStalled(VerificationAttempt attempt) {
        LocalDateTime deadline = attempt.getStageDeadlineUtc();
        return deadline != null && deadline.isBefore(UtcTimes.nowUtc())
                && stateMachine.canTransition(attempt.getStatus(), AttemptStatus.EXPIRED);
    }

    private DecisionRecord toRecord(long attemptId, DecisionOutcome outcome,
                                    InferenceClient.VerifyScores scores, BigDecimal documentScore,
                                    LocalDateTime decidedAt) {
        DecisionRecord record = new DecisionRecord();
        record.setAttemptId(attemptId);
        record.setLivenessScore(scores.attackScore());
        record.setMatchScore(scores.similarity());
        record.setDocValidationScore(documentScore);
        record.setCompositeScore(outcome.compositeScore());
        record.setDecision(outcome.decision());
        record.setReasonCode(outcome.reasonCode());
        record.setGuidanceText(truncate(outcome.guidance(), 256));
        record.setThresholdVersion(outcome.thresholdVersion());
        record.setLivenessModelVer(truncate(scores.livenessModelVersion(), 32));
        record.setEmbeddingModelVer(truncate(scores.embeddingModelVersion(), 32));
        record.setDecidedAtUtc(decidedAt);
        return record;
    }

    /**
     * The contract has no enrolment endpoint, so an authenticated subject id resolves to a
     * placeholder row that attempt and decision history then hang off. No identity attribute is
     * invented: the national id hash is written only by the document payload path. The row's id is
     * the token subject, so it must be marked as an insert rather than merged.
     */
    private UserAccount requireSubject(Actor actor) {
        if (actor.actorId() == null) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED, "a subject id is required");
        }
        return userRepository.findById(actor.actorId()).orElseGet(() -> transactionTemplate.execute(
                status -> {
                    UserAccount created = new UserAccount();
                    created.setId(actor.actorId());
                    created.setFullName("Subject " + actor.actorId());
                    created.setRole(actor.isReviewer() ? "REVIEWER"
                            : actor.isSystem() ? "ADMIN" : "SUBJECT");
                    created.setStatus("PENDING");
                    created.setCreatedAtUtc(UtcTimes.nowUtc());
                    return userRepository.save(created.asNewPlaceholder());
                }));
    }

    private static void requirePayloadKey(byte[] payloadKey) {
        if (payloadKey == null || payloadKey.length != PAYLOAD_KEY_BYTES) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                    "X-Attempt-Key must be base64 of a 256-bit per-attempt payload key");
        }
    }

    private static String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "documentType is required");
        }
        return documentType.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static void requireQualityGate(DocumentPayload.Quality quality) {
        if (quality == null || quality.blurScore() == null || quality.cornersFound() == null) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    "quality.blurScore and quality.cornersFound are required");
        }
        // The on-device gate is the client's job; the service refuses a payload that says it failed.
        if (!Boolean.TRUE.equals(quality.cornersFound())) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    "the quality gate reports the document corners were not found");
        }
        if (quality.blurScore() < 0) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "quality.blurScore must not be negative");
        }
    }

    /**
     * {@code ocr_confidence} is metadata only and never feeds the composite: it is an upper bound
     * on text reliability derived from the declared glare ratio (glare hides glyphs).
     */
    private static BigDecimal storedConfidence(DocumentPayload.Quality quality) {
        if (quality == null || quality.glareRatio() == null) {
            return null;
        }
        double glare = quality.glareRatio();
        if (glare < 0 || glare > 1) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "quality.glareRatio must lie in 0..1");
        }
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(glare)).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal documentScoreOf(DocumentMetadata metadata) {
        // A document that fails either structural check scores zero: no partial credit.
        return metadata.isMrzValid() && metadata.isChecksumValid() ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private Duration stageTtl(AttemptStatus status) {
        return Duration.ofSeconds(deadlineSeconds(status));
    }

    private long deadlineSeconds(AttemptStatus status) {
        AppProperties.Deadline deadline = properties.getDeadline();
        return switch (status) {
            case CREATED -> deadline.getCreatedSeconds();
            case CAPTURED -> deadline.getCapturedSeconds();
            case INFERRING -> deadline.getInferringSeconds();
            case FAILED_RETRYABLE -> deadline.getFailedRetryableSeconds();
            case DECIDED, EXPIRED -> 0;
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
