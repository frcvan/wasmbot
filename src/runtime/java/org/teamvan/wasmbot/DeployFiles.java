package org.teamvan.wasmbot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.wpilib.system.Filesystem;

/**
 * Materializes the consumer's bundled deploy files in the image's in-memory file system.
 *
 * <p>GraalVM Web Image replaces {@code java.io} and {@code java.nio.file} with an in-memory file
 * system, so ordinary file reads only find files that exist there. The build stages {@code
 * src/main/deploy} as classpath resources under {@code /wasmbot/deploy/} plus an index of their
 * names; startup copies each one into {@link Filesystem#getDeployDirectory()} before the robot
 * constructor runs. Libraries that open deploy files, check {@code File.exists()}, or list the
 * deploy directory then see the same files they would on a deployed robot, all through the standard
 * Java file APIs.
 */
public final class DeployFiles {
  private static final String INDEX = "/wasmbot/deploy-index.txt";
  private static final String RESOURCES = "/wasmbot/deploy/";

  private DeployFiles() {}

  /**
   * Copies the bundled deploy files into the image's file system. Repeated calls are harmless; the
   * host calls this once before constructing the robot.
   */
  public static void install() {
    List<String> names = names();
    if (names.isEmpty()) {
      return;
    }
    // The wasm Filesystem shadow reports the same directory, so lookups cannot drift from the copy.
    copy(names, Filesystem.getDeployDirectory().toPath());
  }

  private static List<String> names() {
    try (InputStream index = DeployFiles.class.getResourceAsStream(INDEX)) {
      if (index == null) {
        return List.of();
      }
      List<String> names = new ArrayList<>();
      for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        String name = line.strip();
        if (!name.isEmpty()) {
          names.add(name);
        }
      }
      return names;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the bundled deploy index", e);
    }
  }

  private static void copy(List<String> names, Path directory) {
    for (String name : names) {
      try {
        Path target = directory.resolve(name);
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.write(target, bytes(name));
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to stage deploy file '" + name + "'", e);
      }
    }
  }

  private static byte[] bytes(String name) throws IOException {
    try (InputStream stream = DeployFiles.class.getResourceAsStream(RESOURCES + name)) {
      if (stream == null) {
        throw new IOException("Missing bundled deploy resource '" + name + "'");
      }
      return stream.readAllBytes();
    }
  }
}
