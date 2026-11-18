package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.model.SessionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateMachineTest {

    @Test
    void createdCanStartRunOrBeCancelled() {
        assertTrue(SessionState.isValidTransition(SessionState.CREATED, SessionState.RUNNING));
        assertTrue(SessionState.isValidTransition(SessionState.CREATED, SessionState.CANCELLED));
        assertTrue(SessionState.isValidTransition(SessionState.CREATED, SessionState.ARCHIVED));
        assertFalse(SessionState.isValidTransition(SessionState.CREATED, SessionState.COMPLETED));
    }

    @Test
    void runningTransitions() {
        assertTrue(SessionState.isValidTransition(SessionState.RUNNING, SessionState.SUSPENDED));
        assertTrue(SessionState.isValidTransition(SessionState.RUNNING, SessionState.WAITING_FOR_APPROVAL));
        assertTrue(SessionState.isValidTransition(SessionState.RUNNING, SessionState.COMPLETED));
        assertTrue(SessionState.isValidTransition(SessionState.RUNNING, SessionState.FAILED));
        assertTrue(SessionState.isValidTransition(SessionState.RUNNING, SessionState.CANCELLED));
        assertFalse(SessionState.isValidTransition(SessionState.RUNNING, SessionState.CREATED));
    }

    @Test
    void suspendedIsResumable() {
        assertTrue(SessionState.isValidTransition(SessionState.SUSPENDED, SessionState.RUNNING));
        assertFalse(SessionState.isValidTransition(SessionState.SUSPENDED, SessionState.COMPLETED));
    }

    @Test
    void waitingForApprovalCanReturnToRunning() {
        assertTrue(SessionState.isValidTransition(SessionState.WAITING_FOR_APPROVAL, SessionState.RUNNING));
        assertTrue(SessionState.isValidTransition(SessionState.WAITING_FOR_APPROVAL, SessionState.COMPLETED));
        assertTrue(SessionState.isValidTransition(SessionState.WAITING_FOR_APPROVAL, SessionState.CANCELLED));
    }

    @Test
    void terminalStatesOnlyArchive() {
        for (SessionState terminal : new SessionState[]{
                SessionState.COMPLETED, SessionState.FAILED, SessionState.CANCELLED}) {
            assertTrue(terminal.isTerminal());
            assertTrue(SessionState.isValidTransition(terminal, SessionState.ARCHIVED));
            assertFalse(SessionState.isValidTransition(terminal, SessionState.RUNNING));
            assertFalse(SessionState.isValidTransition(terminal, SessionState.COMPLETED));
        }
        assertFalse(SessionState.isValidTransition(SessionState.ARCHIVED, SessionState.RUNNING));
        assertFalse(SessionState.isValidTransition(SessionState.ARCHIVED, SessionState.CREATED));
    }

    @Test
    void sameStateIsNotATransition() {
        for (SessionState s : SessionState.values()) {
            assertFalse(SessionState.isValidTransition(s, s));
        }
    }
}
