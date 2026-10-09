package id.fayda.verification.infra.inference;

/** Any failure of the inference peer: transport, non-2xx, or a body that cannot be trusted. */
public class InferenceUnavailableException extends RuntimeException {

    private final String requestId;

    public InferenceUnavailableException(String requestId, String message, Throwable cause) {
        super(message, cause);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
