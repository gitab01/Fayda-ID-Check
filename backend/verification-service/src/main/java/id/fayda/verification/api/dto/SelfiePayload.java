package id.fayda.verification.api.dto;

import java.util.List;

/**
 * Plaintext shape of the selfie envelope (CONTRACT §1). Requires at least three frames.
 * {@code executedActions} is the order the client claims it followed and is checked against the
 * sequence re-derived from the attempt's challenge seed.
 */
public record SelfiePayload(List<Frame> frames, List<String> executedActions, Quality quality) {

    public record Frame(String bytes64, String action, Long capturedAtMs) {
    }

    public record Quality(Double blurScore, List<Integer> faceBox) {
    }

    /** faceBox as [x, y, w, h]; exactly four non-negative integers. */
    public List<Integer> faceBoxOrEmpty() {
        if (quality == null || quality.faceBox() == null) {
            return List.of();
        }
        return quality.faceBox();
    }
}
