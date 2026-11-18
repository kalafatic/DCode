package eu.kalafatic.dcode.client;

/** Error raised for non-2xx API responses, carrying the backend error envelope. */
public class DCodeApiException extends RuntimeException {

    private final String code;
    private final int status;

    public DCodeApiException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
