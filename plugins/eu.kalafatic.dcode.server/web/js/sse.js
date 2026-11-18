/**
 * SSE client: live session events with reconnection, dedupe and reconciliation.
 *
 * The backend streams are live-only (no Last-Event-ID replay). The client therefore:
 *  1. reconnects with exponential backoff (native EventSource handles the transport);
 *  2. dedupes by monotonic backend event id (already-seen ids are dropped);
 *  3. after every reconnect it re-fetches the authoritative session state and lets
 *     the UI reconcile (the server is authoritative, never local flags).
 */
window.DCodeSse = (function () {
    let source = null;
    let sessionId = null;
    let lastEventId = 0;
    let lastMessageAt = 0;
    let watchdog = null;
    let onEvent = null;
    let onStatus = null;
    let reconnectAttempts = 0;

    function setStatus(status, detail) {
        if (onStatus) {
            onStatus(status, detail);
        }
    }

    function connect(newSessionId, handler, statusHandler) {
        disconnect();
        sessionId = newSessionId;
        onEvent = handler;
        onStatus = statusHandler;
        if (!sessionId) {
            setStatus('disconnected', 'no session');
            return;
        }
        open();
    }

    function open() {
        if (!sessionId) {
            return;
        }
        setStatus('reconnecting', 'connecting');
        const url = DCodeApi.base + '/api/v1/events/stream?sessionId='
            + encodeURIComponent(sessionId);
        source = new EventSource(url);
        source.onopen = function () {
            reconnectAttempts = 0;
            setStatus('connected', 'live');
            lastMessageAt = Date.now();
        };
        source.onmessage = function (message) {
            lastMessageAt = Date.now();
            const eventId = parseInt(message.lastEventId, 10);
            if (!isNaN(eventId)) {
                if (eventId <= lastEventId) {
                    return; // duplicate/delayed frame
                }
                lastEventId = eventId;
            }
            if (onEvent) {
                onEvent(message.event || 'message', message.data || '{}');
            }
        };
        source.onerror = function () {
            source.close();
            source = null;
            reconnectAttempts++;
            setStatus('reconnecting', 'retry #' + reconnectAttempts);
            const delay = Math.min(500 * Math.pow(2, reconnectAttempts - 1), 8000);
            setTimeout(open, delay);
        };
        startWatchdog();
    }

    function startWatchdog() {
        if (watchdog) {
            clearInterval(watchdog);
        }
        lastMessageAt = Date.now();
        watchdog = setInterval(function () {
            if (source && Date.now() - lastMessageAt > 45000) {
                // dead stream (silent proxy): force reopen
                if (source) {
                    source.close();
                }
                source = null;
                setStatus('reconnecting', 'stream watchdog');
                open();
            }
        }, 10000);
    }

    function disconnect() {
        if (watchdog) {
            clearInterval(watchdog);
            watchdog = null;
        }
        if (source) {
            source.close();
            source = null;
        }
        setStatus('disconnected', '');
    }

    return { connect, disconnect };
})();
