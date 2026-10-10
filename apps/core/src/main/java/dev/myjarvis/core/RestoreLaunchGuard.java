package dev.myjarvis.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Supported-entrypoint Q0 refusal only. This never grants restore admission. */
public final class RestoreLaunchGuard {
    private static final String CONTROL = "JARVIS_RESTORE_CONTROL_DIR";
    private static final String RUNTIME = "JARVIS_RUNTIME_DIR";
    private static final Path ANCHOR = Path.of("restore-enrollment.json");

    private RestoreLaunchGuard() {}

    public static void check(Map<String, String> environment) {
        // Any present value, including blank/false/normal, is restore intent.
        if (environment.containsKey(CONTROL)) throw blocked();
        if (!environment.containsKey(RUNTIME)) return;
        try {
            String raw = environment.get(RUNTIME);
            if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0
                    || raw.getBytes(StandardCharsets.UTF_8).length > 4096) throw blocked();
            Path path = Path.of(raw);
            if (!path.isAbsolute() || !path.toString().equals(raw) || !path.normalize().equals(path)) throw blocked();
            SecureDirectoryStream<Path> opened;
            try {
                opened = open(path);
            } catch (NoSuchFileException absentDirectory) {
                // Ordinary installations may create their runtime directory later.
                return;
            }
            Object before;
            try (SecureDirectoryStream<Path> directory = opened) {
                before = identity(directory);
                try {
                    directory.getFileAttributeView(ANCHOR, BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
                } catch (NoSuchFileException absentAnchor) {
                    // Only absence of this fixed name can keep ordinary admission unchanged.
                    try (SecureDirectoryStream<Path> again = open(path)) {
                        if (!Objects.equals(before, identity(again))) throw blocked();
                    }
                    return;
                }
                throw blocked(); // Any file, directory, link or malformed record denies.
            }
        } catch (IOException | RuntimeException failure) {
            throw blocked(); // Never disclose paths, contents or filesystem exception text.
        }
    }

    private static Object identity(SecureDirectoryStream<Path> directory) throws IOException {
        BasicFileAttributes attributes = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        if (!attributes.isDirectory() || attributes.fileKey() == null) throw blocked();
        return attributes.fileKey();
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> open(Path path) throws IOException {
        DirectoryStream<Path> root = Files.newDirectoryStream(path.getRoot());
        if (!(root instanceof SecureDirectoryStream<?>)) {
            root.close();
            throw blocked(); // No silent insecure traversal fallback on unsupported platforms.
        }
        SecureDirectoryStream<Path> directory = (SecureDirectoryStream<Path>) root;
        try {
            for (Path component : path) {
                SecureDirectoryStream<Path> child = directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                try {
                    Object named = directory.getFileAttributeView(component, BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey();
                    if (!Objects.equals(named, identity(child))) throw blocked();
                } catch (IOException | RuntimeException failure) {
                    child.close();
                    throw failure;
                }
                directory.close();
                directory = child;
            }
            return directory;
        } catch (IOException | RuntimeException failure) {
            directory.close();
            throw failure;
        }
    }

    private static IllegalStateException blocked() {
        return new IllegalStateException("RESTORE_LAUNCH_BLOCKED");
    }
}
