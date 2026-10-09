package id.fayda.verification.service.errors;

/** Carries an {@link ErrorCode} through the layers; the API advice renders the contract body. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static ApiException of(ErrorCode code, String message, Object... args) {
        return new ApiException(code, String.format(message, args));
    }
}
