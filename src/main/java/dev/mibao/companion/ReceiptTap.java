package dev.mibao.companion;

import io.netty.channel.*;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** Always forward to vanilla first; schedule immutable evidence after its main-thread work. */
public final class ReceiptTap extends ChannelInboundHandlerAdapter {
  private final Function<Object, Runnable> capture;
  private final Executor main;

  public ReceiptTap(Function<Object, Runnable> capture, Executor main) {
    this.capture = capture;
    this.main = main;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    Runnable receipt = null;
    try {
      receipt = capture.apply(msg);
    } catch (RuntimeException ignored) {
      /* A failed observer must not consume a game packet. */
    }
    ctx.fireChannelRead(msg);
    if (receipt != null) main.execute(receipt);
  }
}
