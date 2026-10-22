package org.wpilib.system;

import java.io.File;

/**
 * Wasm replacement for WPILib's filesystem paths.
 *
 * <p>Web Image has no host file system, so there is no launch directory, jni build directory, or
 * systemcore root to discover. The runtime stages the consumer's bundled deploy files in the
 * image's in-memory file system under {@code <working directory>/deploy}, and these methods report
 * that same tree: {@link #getDeployDirectory()} is where {@code DeployFiles} copies the resources,
 * so deploy lookups always agree with the staged files. This also gives deploy paths the
 * deployed-robot layout instead of the desktop {@code src/main/deploy} simulation layout.
 */
public final class Filesystem {
  private static final String DEPLOY_DIRECTORY = "deploy";

  private Filesystem() {}

  /**
   * @return the image's working directory, the equivalent of the process working directory
   */
  public static File getLaunchDirectory() {
    return new File(System.getProperty("user.dir", "/")).getAbsoluteFile();
  }

  /**
   * @return the operating directory; the wasm image has a single in-memory root
   */
  public static File getOperatingDirectory() {
    return getLaunchDirectory();
  }

  /**
   * @return the directory holding the consumer's bundled deploy files
   */
  public static File getDeployDirectory() {
    return new File(getOperatingDirectory(), DEPLOY_DIRECTORY);
  }
}
