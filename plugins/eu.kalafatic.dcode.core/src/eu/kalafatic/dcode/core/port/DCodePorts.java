package eu.kalafatic.dcode.core.port;

/**
 * DCode port conventions (family pattern: tens digit identifies the product).
 *
 * <p>EVO uses 28xxx, TRYST 38xxx; DCode occupies the 48xxx range. Proposed values pending
 * product-owner confirmation (see PHASE-5-ARCHITECTURE-BASELINE.md §4.5). Values are
 * defaults only; the configured value always wins and occupied ports fall back through the
 * allocator.</p>
 */
public final class DCodePorts {

    /** Primary HTTP server (REST + SSE + web UI). */
    public static final int HTTP = 48080;

    /** Management server. */
    public static final int MANAGEMENT = 48380;

    /** Auxiliary services. */
    public static final int AUX = 48680;

    /** HTTP fallback search range size (48080..48080+RANGE-1). */
    public static final int HTTP_FALLBACK_RANGE = 500;

    private DCodePorts() {
    }
}
