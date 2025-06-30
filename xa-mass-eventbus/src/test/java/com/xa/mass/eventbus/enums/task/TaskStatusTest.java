package com.xa.mass.eventbus.enums.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskStatus 状态流转测试
 */
public class TaskStatusTest {
    
    @Test
    public void testNewStateTransitions() {
        // NEW 状态只能转换为 BLOCKED 或 TERMINAL
        assertTrue(TaskStatus.NEW.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.NEW.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.NEW.canTransitionTo(TaskStatus.READY));
        assertFalse(TaskStatus.NEW.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.NEW.canTransitionTo(TaskStatus.PAUSED));
    }
    
    @Test
    public void testBlockedStateTransitions() {
        // BLOCKED 状态只能转换为 READY 或 TERMINAL
        assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.READY));
        assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.PAUSED));
    }
    
    @Test
    public void testReadyStateTransitions() {
        // READY 状态可以转换为 RUNNING、BLOCKED 或 TERMINAL
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.RUNNING));
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.READY.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.READY.canTransitionTo(TaskStatus.PAUSED));
    }
    
    @Test
    public void testRunningStateTransitions() {
        // RUNNING 状态可以转换为 BLOCKED、PAUSED 或 TERMINAL
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PAUSED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.READY));
    }
    
    @Test
    public void testPausedStateTransitions() {
        // PAUSED 状态只能转换为 READY 或 TERMINAL
        assertTrue(TaskStatus.PAUSED.canTransitionTo(TaskStatus.READY));
        assertTrue(TaskStatus.PAUSED.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.BLOCKED));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.RUNNING));
    }
    
    @Test
    public void testTerminalStateTransitions() {
        // TERMINAL 状态不能转换为任何其他状态
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.BLOCKED));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.READY));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.PAUSED));
    }
    
    @Test
    public void testStateProperties() {
        // 测试状态属性
        assertTrue(TaskStatus.TERMINAL.isFinal());
        assertFalse(TaskStatus.NEW.isFinal());
        assertFalse(TaskStatus.BLOCKED.isFinal());
        assertFalse(TaskStatus.READY.isFinal());
        assertFalse(TaskStatus.RUNNING.isFinal());
        assertFalse(TaskStatus.PAUSED.isFinal());
        
        assertTrue(TaskStatus.READY.isSchedulable());
        assertFalse(TaskStatus.NEW.isSchedulable());
        assertFalse(TaskStatus.BLOCKED.isSchedulable());
        assertFalse(TaskStatus.RUNNING.isSchedulable());
        assertFalse(TaskStatus.PAUSED.isSchedulable());
        assertFalse(TaskStatus.TERMINAL.isSchedulable());
        
        assertTrue(TaskStatus.RUNNING.isRunning());
        assertFalse(TaskStatus.NEW.isRunning());
        assertFalse(TaskStatus.BLOCKED.isRunning());
        assertFalse(TaskStatus.READY.isRunning());
        assertFalse(TaskStatus.PAUSED.isRunning());
        assertFalse(TaskStatus.TERMINAL.isRunning());
        
        assertTrue(TaskStatus.BLOCKED.isBlocked());
        assertFalse(TaskStatus.NEW.isBlocked());
        assertFalse(TaskStatus.READY.isBlocked());
        assertFalse(TaskStatus.RUNNING.isBlocked());
        assertFalse(TaskStatus.PAUSED.isBlocked());
        assertFalse(TaskStatus.TERMINAL.isBlocked());
        
        assertTrue(TaskStatus.PAUSED.isPaused());
        assertFalse(TaskStatus.NEW.isPaused());
        assertFalse(TaskStatus.BLOCKED.isPaused());
        assertFalse(TaskStatus.READY.isPaused());
        assertFalse(TaskStatus.RUNNING.isPaused());
        assertFalse(TaskStatus.TERMINAL.isPaused());
    }
    
    @Test
    public void testStateDescriptions() {
        assertEquals("新建", TaskStatus.NEW.getDescription());
        assertEquals("已阻塞", TaskStatus.BLOCKED.getDescription());
        assertEquals("待分配", TaskStatus.READY.getDescription());
        assertEquals("运行中", TaskStatus.RUNNING.getDescription());
        assertEquals("已暂停", TaskStatus.PAUSED.getDescription());
        assertEquals("已终止", TaskStatus.TERMINAL.getDescription());
    }
} 