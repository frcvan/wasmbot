package example;

import org.wpilib.command2.SubsystemBase;

/** A hardware-free subsystem whose default command counts enabled scheduler ticks. */
public final class Counter extends SubsystemBase {
  private int ticks;

  public Counter() {
    setDefaultCommand(run(() -> ticks++).withName("Count scheduler ticks"));
  }

  public int ticks() {
    return ticks;
  }
}
