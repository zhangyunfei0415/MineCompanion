package dev.mibao.companion;

public interface LocalRoutine {
  void tick();

  void cancel();

  void fail(String reason);

  boolean finished();
}
