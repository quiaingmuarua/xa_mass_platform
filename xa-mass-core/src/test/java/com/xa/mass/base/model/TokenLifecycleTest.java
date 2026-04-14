package com.xa.mass.base.model;

import com.xa.mass.base.enums.task.TokenStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenLifecycleTest {

    @Test
    void bindSendReleaseFollowsMainlineLifecycle() {
        Token token = new Token("tok-1", "dev-1", "us");

        assertTrue(token.bindToTask("task-1"));
        assertEquals(TokenStatus.RESERVED, token.getStatus());
        assertEquals("task-1", token.getLastBindTaskId());

        assertTrue(token.startOccupying());
        assertEquals(TokenStatus.OCCUPIED, token.getStatus());
        assertNotNull(token.getLastUsedTime());

        assertTrue(token.release());
        assertEquals(TokenStatus.IDLE, token.getStatus());
        assertNull(token.getLastBindTaskId());
    }

    @Test
    void bindRejectsBlankTaskId() {
        Token token = new Token("tok-2", "dev-2", "us");

        assertFalse(token.bindToTask(" "));
        assertEquals(TokenStatus.IDLE, token.getStatus());
    }

    @Test
    void releaseDoesNotActAsBlockedRecovery() {
        Token token = new Token("tok-3", "dev-3", "us");

        assertTrue(token.block());
        assertFalse(token.release());
        assertEquals(TokenStatus.BLOCKED, token.getStatus());

        assertTrue(token.unblock());
        assertEquals(TokenStatus.IDLE, token.getStatus());
    }

    @Test
    void invalidateIsTerminalForTokenStateMachine() {
        Token token = new Token("tok-4", "dev-4", "us");

        assertTrue(token.invalidate());
        assertEquals(TokenStatus.INVALID, token.getStatus());
        assertFalse(token.unblock());
        assertFalse(token.bindToTask("task-2"));
    }

    @Test
    void setStatusRejectsNull() {
        Token token = new Token();

        assertThrows(NullPointerException.class, () -> token.setStatus(null));
    }
}
