package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ThreadSnapshotVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 验证线程快照的结果边界，避免诊断接口退化为无限制的原始 Thread Dump。
 */
class ThreadSnapshotServiceTest {

    @Test
    void snapshotMustLimitThreadsFramesAndSensitiveContent() throws Exception {
        ThreadSnapshotService service = new ThreadSnapshotService(20L, 10, 20);

        ThreadSnapshotVO snapshot = service.snapshot();
        String json = new ObjectMapper().writeValueAsString(snapshot);

        Assertions.assertTrue(snapshot.getThreads().size() <= 10);
        snapshot.getThreads().forEach(thread ->
                Assertions.assertTrue(thread.getStackFrames().size() <= 20)
        );
        Assertions.assertFalse(json.contains("AKSK_SECRET_KEY"));
        Assertions.assertFalse(json.contains("JAVA_TOOL_OPTIONS"));
        Assertions.assertFalse(json.toLowerCase().contains("authorization"));
    }
}
