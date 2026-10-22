package org.teamvan.wasmbot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.graalvm.webimage.api.JS;
import org.wpilib.framework.RobotBase;
import org.wpilib.system.Watchdog;

/** Nonblocking JavaScript-hosted launcher for ordinary WPILib robot programs. */
public final class WasmApplication {
  /** Host clock advancement between ticks, in seconds. */
  public static final double PERIOD_SECONDS = 0.02;

  private final Supplier<? extends RobotBase> factory;
  private RobotBase robot;
  private boolean started;
  private Throwable startupFailure;

  private WasmApplication(Supplier<? extends RobotBase> factory) {
    this.factory = Objects.requireNonNull(factory);
  }

  /**
   * Exports {@code wasmbot.start} and the serialized asynchronous {@code wasmbot.tick} entry point.
   *
   * <p>The host installs {@code wasmbot.nowNanos}, {@code wasmbot.driverStation}, {@code
   * wasmbot.ntGet}, and {@code wasmbot.ntPut} before calling {@code wasmbot.start()}. The factory
   * runs once, with the {@code wasmbot} system property set to {@code true}, after telemetry
   * installation and the initial driver-station refresh. Framework startup initializes simulation
   * without blocking on a native notifier.
   *
   * @param factory constructs the consumer's normal robot
   */
  public static void launch(Supplier<? extends RobotBase> factory) {
    WasmApplication application = new WasmApplication(factory);
    exportErrorFormatter(WasmApplication::errorText);
    exportStart(application::start);
    exportTick(application);
  }

  @JS(
      args = {"start"},
      value = "globalThis.wasmbot.start = start;")
  private static native void exportStart(Runnable start);

  @JS(
      """
      const engine = globalThis.wasmbot.continuations;
      engine.bindTick(tick);
      globalThis.wasmbot.tick = async () => {
        try {
          await engine.tick();
        } catch (error) {
          if (error instanceof WebAssembly.Exception && error.is(getExport('tag.throwable'))) {
            throw conversion.javaToJavaScript(error);
          }
          throw error;
        }
      };
      """)
  private static native void exportTick(WasmApplication tick);

  @JS(
      args = {"format"},
      value = "globalThis.wasmbot.formatJavaError = error => String(format(error));")
  private static native void exportErrorFormatter(Function<Throwable, String> format);

  private static String errorText(Throwable failure) {
    StringWriter out = new StringWriter();
    failure.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  // Runnable cannot declare Throwable, but startup must preserve even an Error or checked failure.
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void rethrow(Throwable failure) throws T {
    throw (T) failure;
  }

  private void start() {
    if (startupFailure != null) {
      rethrow(startupFailure);
    }
    if (started) {
      return;
    }
    started = true;
    try {
      System.setProperty("wasmbot", "true");
      DeployFiles.install();
      JsTelemetry.install();
      JsTunables.install();
      WasmRuntime.refresh();
      robot = Objects.requireNonNull(factory.get(), "Robot constructor returned null");
      robot.startCompetition();
    } catch (Throwable t) {
      startupFailure = t;
      rethrow(t);
    }
  }

  /**
   * Refreshes the driver station and delivers one host tick to the robot framework, including while
   * disabled. The robot's own {@code robotPeriodic()} owns any command-scheduler invocation.
   *
   * <p>This public instance method is the raw JSPI tick target registered in reflection metadata.
   */
  public void tick() {
    start();
    WasmRuntime.refresh();
    Watchdog.poll();
    robot.wasmTick();
    Watchdog.poll();
  }
}
