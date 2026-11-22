package eu.kalafatic.dcode.core.port;

/**
 * DCode port conventions (family standard): {@code port = 8x80 + product offset},
 * offsets: EVO +20000 (28xxx), TRYST +30000 (38xxx), DStudio +40000 (48xxx),
 * DCode +50000 (58xxx). Spacing between services is 100.
 * DStudio owns the 48xxx block (TEIP 48080); DCode owns the 58xxx block so both
 * products can run simultaneously.
 *
 * <p>Values are defaults only; the configured value always wins and occupied ports
 * fall back through the allocator within the DCode family range. Actual assignments
 * are reported by {@code GET /api/v1/ports} and published via
 * {@code dcode.server.port}.</p>
 */
public final class DCodePorts {

    /** Product base offset (family standard). */
    public static final int BASE_OFFSET = 50000;

    /** Service spacing (family standard). */
    public static final int SERVICE_SPACING = 100;

    /** Primary HTTP server (REST + SSE + web UI). Family default for DCode. */
    public static final int HTTP = 58080;

    /** Management server (reserved; not started in the current build). */
    public static final int MANAGEMENT = 58380;

    /** Auxiliary services (reserved). */
    public static final int AUX = 58680;

    /** HTTP fallback search range size (58080..58080+RANGE-1, stays in 58xxx). */
    public static final int HTTP_FALLBACK_RANGE = 500;

    private DCodePorts() {
    }
}
