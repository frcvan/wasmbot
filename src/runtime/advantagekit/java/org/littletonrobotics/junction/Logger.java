/*
 * Copyright (c) 2021-2026 Littleton Robotics. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * - Neither the name of Littleton Robotics, FRC 6328 ("Mechanical Advantage"),
 *   AdvantageKit, nor the names of other AdvantageKit contributors may be
 *   used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY LITTLETON ROBOTICS AND OTHER ADVANTAGEKIT
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY NONINFRINGEMENT
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * LITTLETON ROBOTICS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.littletonrobotics.junction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.LogTable.LogValue;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.littletonrobotics.junction.mechanism.LoggedMechanism2d;
import org.littletonrobotics.junction.networktables.LoggedNetworkInput;
import org.wpilib.system.RobotController;
import org.wpilib.units.Measure;
import org.wpilib.units.Unit;
import org.wpilib.util.Color;
import org.wpilib.util.WPISerializable;
import org.wpilib.util.protobuf.Protobuf;
import org.wpilib.util.struct.Struct;
import org.wpilib.util.struct.StructSerializable;
import us.hebi.quickbuf.ProtoMessage;

/**
 * Live AdvantageKit logging for the single-threaded WebAssembly host.
 *
 * <p>Adapted from AdvantageKit 27.0.0-alpha-6. Receivers run synchronously on the host thread, each
 * with an independent table snapshot. The upstream LogTable, generated LoggableInputs, serializers,
 * dashboard inputs, and NT4Publisher are reused without replacement.
 *
 * <p>Replay, file receivers, native socket receivers, URCL, and console capture are unsupported.
 * There is no hardware/Conduit capture; stdout/stderr remain owned by the host console. Annotated
 * outputs use the upstream AutoLogOutputManager. All methods are intended for the serialized
 * robot/host thread, not concurrent callers.
 */
public class Logger {
  private static boolean running;
  private static long cycleCount;
  private static final LogTable entry = new LogTable(0);
  private static LogTable outputTable;
  private static final Map<String, String> metadata = new HashMap<>();
  private static final List<LoggedNetworkInput> dashboardInputs = new ArrayList<>();
  private static final List<LogDataReceiver> dataReceivers = new ArrayList<>();
  private static int startedReceiverCount;

  private Logger() {}

  /** Replay is unavailable in the live WASM runtime; null leaves live logging selected. */
  public static void setReplaySource(LogReplaySource replaySource) {
    if (replaySource != null) {
      throw new UnsupportedOperationException("AdvantageKit replay is not supported in WASM");
    }
  }

  /**
   * Adds a synchronous, host-safe receiver before start. NT4Publisher is supported. Upstream file
   * and native socket receivers (including subclasses) are rejected before their start hook runs.
   * Custom receivers must not perform unsupported file, hardware, or blocking operations.
   */
  public static void addDataReceiver(LogDataReceiver dataReceiver) {
    Objects.requireNonNull(dataReceiver);
    for (Class<?> type = dataReceiver.getClass(); type != null; type = type.getSuperclass()) {
      String name = type.getName();
      if (name.startsWith("org.littletonrobotics.junction.wpilog.")
          || name.startsWith("org.littletonrobotics.junction.rlog.")) {
        throw new UnsupportedOperationException(
            "AdvantageKit file and native socket receivers are not supported in WASM: " + name);
      }
    }
    if (!running) {
      dataReceivers.add(dataReceiver);
    }
  }

  /** Registers a dashboard input for the before-user hook. Called by upstream dashboard inputs. */
  public static void registerDashboardInput(LoggedNetworkInput dashboardInput) {
    dashboardInputs.add(Objects.requireNonNull(dashboardInput));
  }

  /** Native hardware/URCL capture is unavailable in WASM. */
  public static void registerURCL(Supplier<ByteBuffer[]> logSupplier) {
    throw new UnsupportedOperationException(
        "AdvantageKit URCL hardware capture is not supported in WASM");
  }

  /** Records metadata under RealMetadata during setup, before start. */
  public static void recordMetadata(String key, String value) {
    if (!running) {
      metadata.put(key, value);
    }
  }

  /** Console capture is always disabled; stdout/stderr continue to use the host console. */
  public static void disableConsoleCapture() {}

  public static boolean hasReplaySource() {
    return false;
  }

  /**
   * Starts receivers synchronously and opens the initial logging cycle. Repeated starts are inert.
   */
  public static void start() {
    if (running) {
      return;
    }
    entry.setTimestamp(RobotController.getMonotonicTime());
    outputTable = entry.getSubtable("RealOutputs");
    LogTable metadataTable = entry.getSubtable("RealMetadata");
    for (Map.Entry<String, String> item : metadata.entrySet()) {
      metadataTable.put(item.getKey(), item.getValue());
    }
    running = true;
    try {
      while (startedReceiverCount < dataReceivers.size()) {
        // Include a partially started receiver in cleanup if its start hook fails.
        LogDataReceiver receiver = dataReceivers.get(startedReceiverCount++);
        receiver.start();
      }
      RobotController.setTimeSource(Logger::getTimestamp);
      periodicBeforeUser();
    } catch (RuntimeException | Error failure) {
      endAfterFailure(failure);
      throw failure;
    }
  }

  /** Ends all started receivers synchronously, even if an earlier receiver's end hook fails. */
  public static void end() {
    if (!running) {
      return;
    }
    running = false;
    RobotController.setTimeSource(RobotController::getMonotonicTime);
    int count = startedReceiverCount;
    startedReceiverCount = 0;
    Throwable failure = null;
    for (int i = 0; i < count; i++) {
      try {
        dataReceivers.get(i).end();
      } catch (RuntimeException | Error exception) {
        if (failure == null) {
          failure = exception;
        } else if (failure != exception) {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure instanceof RuntimeException exception) {
      throw exception;
    }
    if (failure != null) {
      throw (Error) failure;
    }
  }

  private static void endAfterFailure(Throwable failure) {
    try {
      end();
    } catch (RuntimeException | Error cleanupFailure) {
      if (cleanupFailure != failure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }

  /** Freezes the host timestamp for the cycle and processes live dashboard inputs. */
  static void periodicBeforeUser() {
    cycleCount++;
    if (running) {
      entry.setTimestamp(RobotController.getMonotonicTime());
      long dashboardStart = RobotController.getMonotonicTime();
      for (int i = 0; i < dashboardInputs.size(); i++) {
        dashboardInputs.get(i).periodic();
      }
      recordOutput(
          "Logger/DashboardInputsMS",
          (RobotController.getMonotonicTime() - dashboardStart) / 1_000_000.0);
    }
  }

  static void periodicAfterUser(long userCodeLength, long periodicBeforeLength) {
    periodicAfterUser(userCodeLength, periodicBeforeLength, null);
  }

  /** Completes the cycle and delivers isolated snapshots without queues, threads, or native IO. */
  static void periodicAfterUser(
      long userCodeLength, long periodicBeforeLength, String extraConsoleData) {
    if (!running) {
      return;
    }
    long afterStart = RobotController.getMonotonicTime();
    AutoLogOutputManager.periodic();
    if (extraConsoleData != null && !extraConsoleData.isEmpty()) {
      // Explicit caller-provided text only; this does not install a console capture source.
      recordOutput("Console", extraConsoleData.trim());
    }
    recordOutput("LoggedRobot/UserCodeMS", userCodeLength / 1_000_000.0);
    long afterLength = RobotController.getMonotonicTime() - afterStart;
    recordOutput("LoggedRobot/LogPeriodicMS", (periodicBeforeLength + afterLength) / 1_000_000.0);
    recordOutput(
        "LoggedRobot/FullCycleMS",
        (periodicBeforeLength + userCodeLength + afterLength) / 1_000_000.0);
    recordOutput("Logger/QueuedCycles", 0);
    try {
      for (int i = 0; i < startedReceiverCount; i++) {
        dataReceivers.get(i).putTable(snapshot(entry));
      }
    } catch (InterruptedException exception) {
      IllegalStateException failure =
          new IllegalStateException("Synchronous AdvantageKit receiver was interrupted", exception);
      endAfterFailure(failure);
      throw failure;
    } catch (RuntimeException | Error failure) {
      endAfterFailure(failure);
      throw failure;
    }
  }

  private static LogTable snapshot(LogTable source) {
    LogTable copy = LogTable.clone(source);
    // Upstream clone isolates the map and timestamp but shares LogValues, whose array getters
    // expose mutable storage. Copy just that storage so a receiver cannot alter another receiver's
    // data, a retained previous cycle, or the live entry. Immutable scalar LogValues are safe to
    // share.
    for (Map.Entry<String, LogValue> field : copy.getAll(false).entrySet()) {
      LogValue value = field.getValue();
      LogValue isolated =
          switch (value.type) {
            case Raw -> new LogValue(value.getRaw(null).clone(), value.customTypeStr);
            case BooleanArray ->
                new LogValue(value.getBooleanArray(null).clone(), value.customTypeStr);
            case IntegerArray ->
                new LogValue(value.getIntegerArray(null).clone(), value.customTypeStr);
            case FloatArray -> new LogValue(value.getFloatArray(null).clone(), value.customTypeStr);
            case DoubleArray ->
                new LogValue(value.getDoubleArray(null).clone(), value.customTypeStr);
            case StringArray ->
                new LogValue(value.getStringArray(null).clone(), value.customTypeStr);
            default -> value;
          };
      field.setValue(isolated);
    }
    return copy;
  }

  /** Hooks for custom host-driven robot bases; no reflective robot-base validation is performed. */
  public static class AdvancedHooks {
    /** Robot-base checking is not used by the host-driven WASM runtime. */
    public static void disableRobotBaseCheck() {}

    public static void invokePeriodicBeforeUser() {
      periodicBeforeUser();
    }

    public static void invokePeriodicAfterUser(long userCodeLength, long periodicBeforeLength) {
      periodicAfterUser(userCodeLength, periodicBeforeLength);
    }

    public static void invokePeriodicAfterUser(
        long userCodeLength, long periodicBeforeLength, String extraConsoleData) {
      periodicAfterUser(userCodeLength, periodicBeforeLength, extraConsoleData);
    }

    /** Installing console capture is unsupported; output belongs to the host console. */
    public static void setConsoleSource(ConsoleSource console) {
      throw new UnsupportedOperationException(
          "AdvantageKit console capture is not supported in WASM");
    }

    private AdvancedHooks() {}
  }

  /** Synchronous receivers have no queue; failures propagate instead of setting a queue fault. */
  public static boolean getReceiverQueueFault() {
    return false;
  }

  /** Returns the cycle timestamp in nanoseconds, or live monotonic time before/after logging. */
  public static long getTimestamp() {
    return running ? entry.getTimestamp() : RobotController.getMonotonicTime();
  }

  public static void runEveryN(int n, Runnable function) {
    if (cycleCount % n == 0) {
      function.run();
    }
  }

  /** Records inputs through the upstream serializer; live WASM never replays fromLog. */
  public static void processInputs(String key, LoggableInputs inputs) {
    if (running) {
      inputs.toLog(entry.getSubtable(key));
    }
  }

  // Preserve the complete upstream output overload API and delegate serialization to LogTable.
  public static void recordOutput(String key, byte[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, byte[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, boolean value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, BooleanSupplier value) {
    if (running) {
      outputTable.put(key, value.getAsBoolean());
    }
  }

  public static void recordOutput(String key, boolean[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, boolean[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, int value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, IntSupplier value) {
    if (running) {
      outputTable.put(key, value.getAsInt());
    }
  }

  public static void recordOutput(String key, int[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, int[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, long value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, LongSupplier value) {
    if (running) {
      outputTable.put(key, value.getAsLong());
    }
  }

  public static void recordOutput(String key, long[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, long[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, float value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, float value, Unit unit) {
    if (running) {
      outputTable.put(key, value, unit.name());
    }
  }

  public static void recordOutput(String key, float value, String unit) {
    if (running) {
      outputTable.put(key, value, unit);
    }
  }

  public static void recordOutput(String key, float[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, float[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, double value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, double value, Unit unit) {
    if (running) {
      outputTable.put(key, value, unit.name());
    }
  }

  public static void recordOutput(String key, double value, String unit) {
    if (running) {
      outputTable.put(key, value, unit);
    }
  }

  public static void recordOutput(String key, DoubleSupplier value) {
    if (running) {
      outputTable.put(key, value.getAsDouble());
    }
  }

  public static void recordOutput(String key, double[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, double[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, String value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, String[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, String[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <E extends Enum<E>> void recordOutput(String key, E value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <E extends Enum<E>> void recordOutput(String key, E[] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <E extends Enum<E>> void recordOutput(String key, E[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <U extends Unit> void recordOutputMeasure(String key, Measure<U> value) {
    if (running && value != null) {
      // The measure overload of LogTable is intended primarily for input logging and
      // always uses the base unit. Calling the double overload ensures that the
      // user-specified unit is preserved.
      outputTable.put(key, value.magnitude(), value.unit().name());
    }
  }

  public static <T> void recordOutput(String key, Struct<T> struct, T value) {
    if (running) {
      outputTable.put(key, struct, value);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> void recordOutput(String key, Struct<T> struct, T... value) {
    if (running) {
      outputTable.put(key, struct, value);
    }
  }

  public static <T> void recordOutput(String key, Struct<T> struct, T[][] value) {
    if (running) {
      outputTable.put(key, struct, value);
    }
  }

  public static <T, MessageType extends ProtoMessage<?>> void recordOutput(
      String key, Protobuf<T, MessageType> proto, T value) {
    if (running) {
      outputTable.put(key, proto, value);
    }
  }

  public static <T extends WPISerializable> void recordOutput(String key, T value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T extends StructSerializable> void recordOutput(String key, T... value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <T extends StructSerializable> void recordOutput(String key, T[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  @SuppressWarnings("unchecked")
  public static <R extends Record> void recordOutput(String key, R value) {
    if (value instanceof Measure<?> measure) {
      recordOutputMeasure(key, (Measure<Unit>) measure);
      return;
    }
    if (running) {
      outputTable.put(key, value);
    }
  }

  @SuppressWarnings("unchecked")
  public static <R extends Record> void recordOutput(String key, R... value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static <R extends Record> void recordOutput(String key, R[][] value) {
    if (running) {
      outputTable.put(key, value);
    }
  }

  public static void recordOutput(String key, LoggedMechanism2d value) {
    if (running) {
      value.logOutput(outputTable.getSubtable(key));
    }
  }

  public static void recordOutput(String key, Color value) {
    if (running) {
      outputTable.put(key, value);
    }
  }
}
