-- Seeds the decision profile the service falls back on when no row is active, so a fresh
-- environment decides on the same numbers a laptop demo does.
-- These values are byte-for-byte the ones in src/main/resources/threshold-defaults.yml;
-- DecisionProfileSeedTest asserts the two sources never drift.
INSERT INTO threshold_profiles (version, weight_liveness, weight_match, weight_document,
                                pass_composite, review_composite, min_liveness, min_match,
                                max_attempts, active, calibrated_on)
VALUES ('2026-05-1', 0.3000, 0.5500, 0.1500, 0.86000, 0.72000, 0.35000, 0.48000,
        3, 1, 'bundled-default:heldout-2026-04');
