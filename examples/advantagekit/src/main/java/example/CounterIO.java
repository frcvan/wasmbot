package example;

import org.littletonrobotics.junction.AutoLog;

public interface CounterIO {
  @AutoLog
  class CounterInputs {
    public long count;
    public double[] samples = new double[2];
  }
}
