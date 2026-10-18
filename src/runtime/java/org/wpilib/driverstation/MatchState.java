package org.wpilib.driverstation;

import java.util.Optional;
import org.teamvan.wasmbot.WasmRuntime;

/** Wasm stand-in. Alliance comes from JavaScript, not the FMS. */
public final class MatchState {
  private MatchState() {}

  /**
   * @return the alliance from the latest JavaScript DriverStation snapshot
   */
  public static Optional<Alliance> getAlliance() {
    return WasmRuntime.getAlliance();
  }

  /** No FMS or practice clock is supplied by this host; -1 is the SDK unavailable sentinel. */
  public static double getMatchTime() {
    return -1.0;
  }

  /** This simulator has no FMS game-specific message. */
  public static Optional<String> getGameData() {
    return Optional.empty();
  }
}
