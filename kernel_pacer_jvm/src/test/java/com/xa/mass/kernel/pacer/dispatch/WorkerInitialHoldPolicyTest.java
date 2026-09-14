package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerInitialHoldPolicyTest {
    @Test void initialHoldUsesFiveSecondsAndReturnsOnlyOwnerAcceptedFences() {
        var scores=mock(WorkerScoreCore.class);
        var policy=new WorkerInitialHoldPolicy(scores,500L,() -> 1000);
        when(scores.observeDueHotScoreCandidates("g",500L,2)).thenReturn(Map.of("w",123L));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",123L),6000L)).thenReturn(Map.of("w",
                new WorkerScoreCore.WorkerScoreTransitionResult(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED,456L)));
        var held=policy.any("g",2);
        assertEquals(1,held.size()); assertEquals(456L,held.getFirst().score());
        assertEquals(6000L,held.getFirst().expiresAtMillis());
        verify(scores,never()).getScoreStates(anyString(),anyList());
        verify(scores,never()).confirmActiveHotScoreLeases(anyString(),anyMap(),anyLong());
    }
    @Test void explicitSelectionObservesAllBoundedIdsBeforeApplyingHoldLimit() {
        var scores=mock(WorkerScoreCore.class);
        var policy=new WorkerInitialHoldPolicy(scores,null,() -> 1000);
        when(scores.observeDueHotScores("g",List.of("busy","ready"),null)).thenReturn(Map.of("ready",22L));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("ready",22L),6000L)).thenReturn(Map.of("ready",
                new WorkerScoreCore.WorkerScoreTransitionResult(WorkerScoreCore.WorkerScoreTransitionStatus.STALE,null)));
        assertTrue(policy.identities("g",List.of("busy","ready"),1).isEmpty());
        verify(scores).acquireObservedHotScoreLeases("g",Map.of("ready",22L),6000L);
        verify(scores).observeDueHotScores("g",List.of("busy","ready"),null);
        verifyNoMoreInteractions(scores);
    }
}
