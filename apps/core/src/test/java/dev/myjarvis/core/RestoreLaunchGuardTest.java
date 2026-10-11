package dev.myjarvis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreLaunchGuardTest {
    @TempDir Path directory;

    @Test void everyPresentControlValueDeniesWithoutReadingIt() {
        for (String value : new String[]{"", "false", "normal", "/nonexistent", "malformed"}) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> RestoreLaunchGuard.check(Map.of("JARVIS_RESTORE_CONTROL_DIR", value)));
            assertEquals("RESTORE_LAUNCH_BLOCKED", failure.getMessage());
        }
    }

    @Test void ordinaryUnenrolledAdmissionIsUnchanged() {
        assertDoesNotThrow(() -> RestoreLaunchGuard.check(Map.of()));
        assertDoesNotThrow(() -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", directory.toString())));
        assertDoesNotThrow(() -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", directory.resolve("missing").toString())));
    }

    @Test void anyAnchorObjectDeniesWithoutOpeningContents() throws Exception {
        Path anchor = directory.resolve("restore-enrollment.json");
        Files.writeString(anchor, "invalid metadata including a foreign control path");
        assertThrows(IllegalStateException.class,
                () -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", directory.toString())));
        Files.delete(anchor);
        Files.createDirectory(anchor);
        assertThrows(IllegalStateException.class,
                () -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", directory.toString())));
        Files.delete(anchor);
        Files.createSymbolicLink(anchor, directory.resolve("absent"));
        assertThrows(IllegalStateException.class,
                () -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", directory.toString())));
    }

    @Test void symlinkRuntimeAndInvalidPathsDeny() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        for (String path : new String[]{link.toString(), "relative", "", directory + "/../other"}) {
            assertThrows(IllegalStateException.class,
                    () -> RestoreLaunchGuard.check(Map.of("JARVIS_RUNTIME_DIR", path)));
        }
    }

    @Test void coreRefusesBeforeSpringAndBootstrapBeforeJdbcOrTty() throws Exception {
        try (var spring = mockStatic(SpringApplication.class); var jdbc = mockStatic(DriverManager.class)) {
            Map<String, String> enrolled = Map.of("JARVIS_RESTORE_CONTROL_DIR", "");
            assertEquals("RESTORE_LAUNCH_BLOCKED", assertThrows(IllegalStateException.class,
                    () -> CoreApplication.launch(new String[]{}, enrolled)).getMessage());
            assertEquals("RESTORE_LAUNCH_BLOCKED", assertThrows(IllegalStateException.class,
                    () -> BootstrapAdmin.launch(new String[]{}, enrolled)).getMessage());
            spring.verifyNoInteractions();
            jdbc.verifyNoInteractions();
        }
    }

    @Test void ordinaryCoreStillCallsTheExistingSpringEntry() {
        String[] args = {"--synthetic-test"};
        try (var spring = mockStatic(SpringApplication.class)) {
            CoreApplication.launch(args, Map.of());
            spring.verify(() -> SpringApplication.run(CoreApplication.class, args));
        }
    }
}
