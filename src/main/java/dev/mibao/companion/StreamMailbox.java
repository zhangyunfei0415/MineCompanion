package dev.mibao.companion;

import java.util.concurrent.atomic.AtomicReference;

/** One replaceable snapshot, not one queued main-thread task per token. */
public final class StreamMailbox {
  private final AtomicReference<ProviderStream.Preview> pending = new AtomicReference<>();

  public void publish(ProviderStream.Preview value) {
    pending.set(value);
  }

  public ProviderStream.Preview drain() {
    return pending.getAndSet(null);
  }
}
