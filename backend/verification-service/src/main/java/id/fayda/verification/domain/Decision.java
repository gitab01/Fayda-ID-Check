package id.fayda.verification.domain;

/** Outcome of the decision composite; matches {@code ck_decision} on {@code decision_records}. */
public enum Decision {
    PASS,
    REVIEW,
    FAIL
}
