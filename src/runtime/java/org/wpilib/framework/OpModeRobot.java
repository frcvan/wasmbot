package org.wpilib.framework;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.wpilib.driverstation.RobotState;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.internal.PeriodicPriorityQueue;
import org.wpilib.opmode.Autonomous;
import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Teleop;
import org.wpilib.opmode.Utility;
import org.wpilib.system.RobotController;
import org.wpilib.tunable.TunableRegistry;
import org.wpilib.util.Color;
import org.wpilib.util.ConstructorMatch;

/**
 * Host-driven implementation of the WPILib 2027 opmode robot lifecycle.
 *
 * <p>The browser driver station publishes the opmodes registered through {@link #addOpMode} and
 * lets the user select one for the current robot mode. Opmodes are constructed when selected and
 * follow the SDK's disabled, start, periodic, end, and close lifecycle. There is no native
 * notifier, watchdog, or blocking competition loop; {@link #wasmLoop()} drains the host's due
 * callbacks.
 */
public abstract class OpModeRobot extends RobotBase {
  public static final double DEFAULT_PERIOD = 0.02;

  private record OpModeFactory(RobotMode mode, String name, Supplier<OpMode> supplier) {}

  private final HostPeriodicCallbacks callbacks = new HostPeriodicCallbacks();
  private final Map<Long, OpModeFactory> opModes = new HashMap<>();
  private final PriorityQueue<PeriodicPriorityQueue.Callback> opModeCallbacks =
      new PriorityQueue<>();
  private final Set<PeriodicPriorityQueue.Callback> activeOpModeCallbacks = new HashSet<>();
  private final double period;
  private boolean calledDriverStationConnected;
  private boolean lastEnabled;
  private long lastModeId = -1;
  private OpMode currentOpMode;
  private boolean currentOpModeStarted;

  public OpModeRobot() {
    this(DEFAULT_PERIOD);
  }

  public OpModeRobot(double period) {
    this.period = period;
    addPeriodic(this::loopFunc, period);
  }

  /** Registers a phase-aligned callback; only due callbacks run when the host next ticks. */
  public void addPeriodic(Runnable callback, double period) {
    callbacks.add(callback, period, 0);
  }

  public double getPeriod() {
    return period;
  }

  public long getLoopStartTime() {
    return callbacks.getLoopStartTime();
  }

  public void driverStationConnected() {}

  public void robotPeriodic() {}

  public void simulationInit() {}

  public void simulationPeriodic() {}

  public void disabledInit() {}

  public void disabledPeriodic() {}

  public void disabledExit() {}

  public void nonePeriodic() {}

  /**
   * Adds an opmode created by a factory. Call {@link #publishOpModes()} afterwards to make it
   * visible in the browser.
   */
  public void addOpMode(
      RobotMode mode,
      String name,
      String group,
      String description,
      Color textColor,
      Color backgroundColor,
      Supplier<OpMode> factory) {
    if (factory == null) {
      throw new IllegalArgumentException("OpMode factory must not be null");
    }
    long id = RobotState.addOpMode(mode, name, group, description, textColor, backgroundColor);
    opModes.put(id, new OpModeFactory(mode, name, factory));
  }

  public void addOpMode(
      RobotMode mode, String name, String group, String description, Supplier<OpMode> factory) {
    addOpMode(mode, name, group, description, null, null, factory);
  }

  public void addOpMode(RobotMode mode, String name, String group, Supplier<OpMode> factory) {
    addOpMode(mode, name, group, "", factory);
  }

  public void addOpMode(RobotMode mode, String name, Supplier<OpMode> factory) {
    addOpMode(mode, name, "", factory);
  }

  public void addOpMode(
      RobotMode mode,
      String name,
      String group,
      String description,
      Color textColor,
      Color backgroundColor,
      Class<? extends OpMode> cls) {
    checkOpModeClass(cls);
    addOpMode(
        mode,
        name,
        group,
        description,
        textColor,
        backgroundColor,
        () -> constructOpModeClass(cls));
  }

  public void addOpMode(
      RobotMode mode, String name, String group, String description, Class<? extends OpMode> cls) {
    addOpMode(mode, name, group, description, null, null, cls);
  }

  public void addOpMode(RobotMode mode, String name, String group, Class<? extends OpMode> cls) {
    addOpMode(mode, name, group, "", cls);
  }

  public void addOpMode(RobotMode mode, String name, Class<? extends OpMode> cls) {
    addOpMode(mode, name, "", cls);
  }

  public void addAnnotatedOpMode(Class<? extends OpMode> cls) {
    Autonomous auto = cls.getAnnotation(Autonomous.class);
    Teleop teleop = cls.getAnnotation(Teleop.class);
    Utility utility = cls.getAnnotation(Utility.class);
    if (auto == null && teleop == null && utility == null) {
      throw new IllegalArgumentException("must be annotated with Autonomous, Teleop, or Utility");
    }
    addAnnotatedOpModeImpl(cls, auto, teleop, utility);
  }

  /**
   * Scans a package and its subpackages for classes annotated with {@link Autonomous}, {@link
   * Teleop}, or {@link Utility}. Native images only expose classes and resources that survived
   * native-image analysis; prefer explicit registration when reflection metadata is unavailable.
   */
  public void addAnnotatedOpModeClasses(Package pkg) {
    if (pkg == null) return;
    String packageName = pkg.getName();
    String packagePath = packageName.replace('.', '/');
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    try {
      Enumeration<URL> resources = classLoader.getResources(packagePath);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        if ("jar".equals(resource.getProtocol())) {
          var connection = resource.openConnection();
          if (!(connection instanceof JarURLConnection jarConnection)) {
            System.err.println(
                "Error scanning OpModes from "
                    + resource
                    + ": expected JarURLConnection, got "
                    + connection.getClass().getSimpleName());
            continue;
          }
          jarConnection.setUseCaches(false);
          try (JarFile jar = jarConnection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
              String name = entries.nextElement().getName();
              if (name.startsWith(packagePath) && name.endsWith(".class")) {
                addAnnotatedOpModeClass(name);
              }
            }
          }
        } else if ("file".equals(resource.getProtocol())) {
          File dir = new File(resource.toURI());
          if (dir.isDirectory()) {
            addAnnotatedOpModeClassesDir(dir, dir, packagePath);
          }
        }
      }
    } catch (IOException | URISyntaxException | RuntimeException e) {
      System.err.println("Error scanning annotated OpModes: " + e);
    }
  }

  /** Removes an operating mode option. Call {@link #publishOpModes()} to hide it in the browser. */
  public void removeOpMode(RobotMode mode, String name) {
    long id = RobotState.removeOpMode(mode, name);
    if (id != 0) {
      opModes.remove(id);
    }
  }

  /** Publishes the operating mode options to the browser driver station. */
  public void publishOpModes() {
    RobotState.publishOpModes();
  }

  /** Clears all operating mode options and publishes an empty list. */
  public void clearOpModes() {
    RobotState.clearOpModes();
    opModes.clear();
  }

  private void addAnnotatedOpModeImpl(
      Class<? extends OpMode> cls, Autonomous auto, Teleop teleop, Utility utility) {
    checkOpModeClass(cls);

    if (auto != null) {
      addOpModeClassImpl(
          cls,
          RobotMode.AUTONOMOUS,
          auto.name(),
          auto.group(),
          auto.description(),
          auto.textColor(),
          auto.backgroundColor());
    }
    if (teleop != null) {
      addOpModeClassImpl(
          cls,
          RobotMode.TELEOPERATED,
          teleop.name(),
          teleop.group(),
          teleop.description(),
          teleop.textColor(),
          teleop.backgroundColor());
    }
    if (utility != null) {
      addOpModeClassImpl(
          cls,
          RobotMode.UTILITY,
          utility.name(),
          utility.group(),
          utility.description(),
          utility.textColor(),
          utility.backgroundColor());
    }
  }

  private void addOpModeClassImpl(
      Class<? extends OpMode> cls,
      RobotMode mode,
      String name,
      String group,
      String description,
      String textColor,
      String backgroundColor) {
    String resolvedName = name == null || name.isBlank() ? cls.getSimpleName() : name;
    Color tColor = textColor == null || textColor.isBlank() ? null : Color.fromString(textColor);
    Color bColor =
        backgroundColor == null || backgroundColor.isBlank()
            ? null
            : Color.fromString(backgroundColor);
    addOpMode(
        mode, resolvedName, group, description, tColor, bColor, () -> constructOpModeClass(cls));
  }

  private void addAnnotatedOpModeClass(String name) {
    String className = name.replace('/', '.').substring(0, name.length() - 6);
    Class<? extends OpMode> cls;
    try {
      cls =
          Class.forName(className, false, Thread.currentThread().getContextClassLoader())
              .asSubclass(OpMode.class);
    } catch (ClassNotFoundException | ClassCastException | LinkageError e) {
      return;
    }
    Autonomous auto = cls.getAnnotation(Autonomous.class);
    Teleop teleop = cls.getAnnotation(Teleop.class);
    Utility utility = cls.getAnnotation(Utility.class);
    if (auto == null && teleop == null && utility == null) {
      return;
    }
    try {
      addAnnotatedOpModeImpl(cls, auto, teleop, utility);
    } catch (IllegalArgumentException e) {
      System.err.println("Error adding OpMode " + cls.getSimpleName() + ": " + e.getMessage());
    }
  }

  private void addAnnotatedOpModeClassesDir(File root, File dir, String packagePath) {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (file.isDirectory()) {
        addAnnotatedOpModeClassesDir(root, file, packagePath);
      } else if (file.getName().endsWith(".class")) {
        String relPath = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        addAnnotatedOpModeClass(packagePath + "." + relPath);
      }
    }
  }

  private <T extends OpMode> T constructOpModeClass(Class<T> cls) {
    Optional<ConstructorMatch<T>> constructor =
        ConstructorMatch.findBestConstructor(cls, getClass());
    if (constructor.isEmpty()) {
      constructor = ConstructorMatch.findBestConstructor(cls);
    }
    if (constructor.isEmpty()) {
      throw new IllegalArgumentException(
          "No suitable public constructor for OpMode " + cls.getSimpleName());
    }
    try {
      return constructor.get().newInstance(this);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(
          "Could not instantiate OpMode " + cls.getSimpleName(), e.getCause());
    }
  }

  private void checkOpModeClass(Class<?> cls) {
    if (cls == null || !OpMode.class.isAssignableFrom(cls)) {
      throw new IllegalArgumentException("not a subclass of OpMode");
    }
    int modifiers = cls.getModifiers();
    if (Modifier.isAbstract(modifiers)) {
      throw new IllegalArgumentException("is abstract");
    }
    if (!Modifier.isPublic(modifiers)) {
      throw new IllegalArgumentException("not public");
    }
    if (cls.getEnclosingClass() != null && !Modifier.isStatic(modifiers)) {
      throw new IllegalArgumentException("is a non-static inner class");
    }
    if (ConstructorMatch.findBestConstructor(cls, getClass()).isEmpty()
        && ConstructorMatch.findBestConstructor(cls).isEmpty()) {
      throw new IllegalArgumentException(
          "missing public no-arg constructor or constructor accepting "
              + getClass().getSimpleName());
    }
  }

  private long resolveOpModeId(long id, String name, RobotMode mode) {
    OpModeFactory direct = opModes.get(id);
    if (direct != null && direct.mode() == mode) {
      return id;
    }
    if (name != null && !name.isEmpty()) {
      for (Map.Entry<Long, OpModeFactory> entry : opModes.entrySet()) {
        OpModeFactory candidate = entry.getValue();
        if (candidate.mode() == mode && candidate.name().equals(name)) {
          return entry.getKey();
        }
      }
    }
    return 0;
  }

  private void loopFunc() {
    boolean enabled = RobotState.isEnabled();
    RobotMode mode = RobotState.getRobotMode();
    long selectedId = resolveOpModeId(RobotState.getOpModeId(), RobotState.getOpMode(), mode);

    if (!calledDriverStationConnected && RobotState.isDSAttached()) {
      calledDriverStationConnected = true;
      driverStationConnected();
    }

    boolean modeChanged = selectedId != lastModeId;
    lastModeId = selectedId;
    if (modeChanged && currentOpMode != null) {
      endCurrentOpMode();
    }

    boolean justCreatedOpMode = false;
    if (selectedId != 0 && currentOpMode == null && modeChanged) {
      OpModeFactory factory = opModes.get(selectedId);
      if (factory != null) {
        currentOpMode = factory.supplier().get();
        if (currentOpMode != null) {
          Set<PeriodicPriorityQueue.Callback> callbacksFromOpMode = currentOpMode.getCallbacks();
          if (callbacksFromOpMode != null) {
            activeOpModeCallbacks.addAll(callbacksFromOpMode);
            opModeCallbacks.addAll(callbacksFromOpMode);
          }
          currentOpMode.disabledPeriodic();
          justCreatedOpMode = true;
        }
      }
    }

    // Match OpModeRobot, whose initial state is disabled: disabledInit is only a transition hook,
    // and disabledPeriodic is skipped on the same loop that calls disabledInit.
    boolean justDisabled = false;
    if (lastEnabled != enabled) {
      if (enabled) {
        disabledExit();
      } else {
        if (currentOpMode != null && currentOpModeStarted) {
          endCurrentOpMode();
          // Force construction of the same opmode when the robot is enabled again.
          lastModeId = -1;
        }
        disabledInit();
        justDisabled = true;
      }
      lastEnabled = enabled;
    }

    if (enabled && currentOpMode != null && !currentOpModeStarted) {
      startCurrentOpMode();
    }

    if (!enabled) {
      if (!justDisabled) {
        disabledPeriodic();
      }
      if (currentOpMode != null && !justCreatedOpMode) {
        currentOpMode.disabledPeriodic();
      }
    }

    if (selectedId == 0) {
      nonePeriodic();
    }

    if (enabled && currentOpMode != null && currentOpModeStarted) {
      currentOpMode.periodic();
    }
    runOpModeCallbacks();
    robotPeriodic();
    TunableRegistry.update();
    simulationPeriodic();
  }

  private void startCurrentOpMode() {
    if (currentOpMode == null || currentOpModeStarted) return;
    currentOpModeStarted = true;
    currentOpMode.start();
  }

  private void endCurrentOpMode() {
    if (currentOpMode == null) return;
    if (currentOpModeStarted) {
      currentOpMode.end();
      currentOpModeStarted = false;
    }
    opModeCallbacks.removeAll(activeOpModeCallbacks);
    activeOpModeCallbacks.clear();
    currentOpMode.close();
    currentOpMode = null;
  }

  private void runOpModeCallbacks() {
    long now = RobotController.getMonotonicTime();
    while (!opModeCallbacks.isEmpty() && opModeCallbacks.peek().expirationTime <= now) {
      PeriodicPriorityQueue.Callback callback = opModeCallbacks.poll();
      callback.expirationTime +=
          callback.period + (now - callback.expirationTime) / callback.period * callback.period;
      opModeCallbacks.add(callback);
      callback.func.run();
    }
  }

  /** Initializes simulation exactly once and returns; the host drives the callback queue. */
  @Override
  public final void startCompetition() {
    if (beginCompetition()) {
      simulationInit();
    }
  }

  @Override
  protected final void wasmLoop() {
    callbacks.runDue();
  }

  @Override
  public final void endCompetition() {
    stopCompetition();
    endCurrentOpMode();
    callbacks.clear();
    opModeCallbacks.clear();
    activeOpModeCallbacks.clear();
  }

  public void printWatchdogEpochs() {
    throw new UnsupportedOperationException(
        "The host-driven framework does not run a native watchdog");
  }
}
