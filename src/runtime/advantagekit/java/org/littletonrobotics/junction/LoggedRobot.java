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

import org.wpilib.framework.HostPeriodicCallbacks;
import org.wpilib.framework.IterativeRobotBase;
import org.wpilib.system.RobotController;

/**
 * AdvantageKit's iterative robot lifecycle driven by nonblocking WebAssembly host ticks.
 *
 * <p>Adapted from AdvantageKit 27.0.0-alpha-6. Initialization belongs in the robot constructor (the
 * 2027 WPILib API), followed by simulationInit exactly once at competition startup. Logging hooks
 * surround each user cycle. There are no HAL notifiers, native threads, garbage-collector
 * management beans, or hardware capture. Annotated outputs are discovered after simulationInit.
 *
 * <p>Timed cycles use the shared host scheduler: missed expirations are skipped, not replayed.
 * Untimed execution runs one cycle per host tick, never a busy loop.
 */
public class LoggedRobot extends IterativeRobotBase {
  public static final double defaultPeriodSecs = 0.02;

  private final HostPeriodicCallbacks callbacks = new HostPeriodicCallbacks();
  private boolean useTiming = true;

  protected LoggedRobot() {
    this(defaultPeriodSecs);
  }

  /** Creates a logged robot with a positive finite period in seconds. */
  protected LoggedRobot(double period) {
    super(period);
    callbacks.add(this::runLoggedCycle, period, period);
  }

  /** Initializes once, flushes constructor/init logs, and returns without waiting for a tick. */
  @Override
  public void startCompetition() {
    if (!beginCompetition()) {
      return;
    }
    try {
      long initStart = RobotController.getMonotonicTime();
      simulationInit();
      long initEnd = RobotController.getMonotonicTime();
      AutoLogOutputManager.addObject(this);
      Logger.periodicAfterUser(initEnd - initStart, 0);
    } catch (RuntimeException | Error failure) {
      stopAfterFailure(failure);
      throw failure;
    }
  }

  @Override
  protected void wasmLoop() {
    if (useTiming) {
      callbacks.runDue();
    } else {
      runLoggedCycle();
    }
  }

  private void runLoggedCycle() {
    long beforeStart = RobotController.getMonotonicTime();
    long userStart = beforeStart;
    boolean afterStarted = false;
    try {
      Logger.periodicBeforeUser();
      userStart = RobotController.getMonotonicTime();
      loopFunc();
      long userEnd = RobotController.getMonotonicTime();
      afterStarted = true;
      Logger.periodicAfterUser(userEnd - userStart, userStart - beforeStart);
    } catch (RuntimeException | Error failure) {
      if (!afterStarted) {
        try {
          Logger.periodicAfterUser(
              RobotController.getMonotonicTime() - userStart, userStart - beforeStart);
        } catch (RuntimeException | Error loggingFailure) {
          if (loggingFailure != failure) {
            failure.addSuppressed(loggingFailure);
          }
        }
      }
      stopAfterFailure(failure);
      throw failure;
    }
  }

  /** Permanently stops user callbacks and synchronously closes logging receivers. */
  @Override
  public void endCompetition() {
    stopCompetition();
    callbacks.clear();
    Logger.end();
  }

  /**
   * Selects the configured period when true, or one cycle on every host tick when false. Untimed
   * operation remains host-paced and nonblocking; there is no native/replay fast-running loop.
   */
  public void setUseTiming(boolean useTiming) {
    this.useTiming = useTiming;
  }

  private void stopAfterFailure(Throwable failure) {
    try {
      endCompetition();
    } catch (RuntimeException | Error cleanupFailure) {
      if (cleanupFailure != failure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }
}
