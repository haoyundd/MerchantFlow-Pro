package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;

/**
 * 验证 CPU 故障入口只能在 Lab Profile 中注册，并且令牌与执行时长均有硬边界。
 */
class LabFaultControllerTest {

    @Test
    void controllerMustBeRestrictedToLabProfile() {
        Profile profile = LabFaultController.class.getAnnotation(Profile.class);
        Assertions.assertNotNull(profile);
        Assertions.assertArrayEquals(new String[]{"lab"}, profile.value());
    }

    @Test
    void cpuLoadMustRejectInvalidTokenAndDuration() {
        LabFaultController controller = new LabFaultController("lab-token");

        Assertions.assertEquals(403, controller.cpuLoad("wrong", 100).getStatusCodeValue());
        Assertions.assertEquals(400, controller.cpuLoad("lab-token", 99).getStatusCodeValue());
        Assertions.assertEquals(400, controller.cpuLoad("lab-token", 5001).getStatusCodeValue());
    }

    @Test
    void cpuLoadMustFinishWithinBoundedTime() {
        LabFaultController controller = new LabFaultController("lab-token");
        long started = System.nanoTime();

        ResponseEntity<Result> response = controller.cpuLoad("lab-token", 100);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        Assertions.assertEquals(200, response.getStatusCodeValue());
        Assertions.assertTrue(response.getBody() != null && response.getBody().getSuccess());
        Assertions.assertTrue(elapsedMs >= 80 && elapsedMs < 1000);
    }
}
