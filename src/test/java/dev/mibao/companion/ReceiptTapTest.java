package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReceiptTapTest {
  @Test
  void observerFailureDoesNotConsumeTheGamePacket() {
    var channel =
        new EmbeddedChannel(
            new ReceiptTap(
                msg -> {
                  throw new IllegalArgumentException("bad observer");
                },
                Runnable::run));
    try {
      assertTrue(channel.writeInbound("full-menu"));
      assertEquals("full-menu", channel.readInbound());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void vanillaWorkIsQueuedBeforeEvidenceAndPacketsAreForwardedExactlyOnce() {
    List<String> result = new ArrayList<>();
    Deque<Runnable> clientQueue = new ArrayDeque<>();
    var tap = new ReceiptTap(msg -> () -> result.add("receipt:" + msg), clientQueue::add);
    var vanilla =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) {
            clientQueue.add(() -> result.add("vanilla:" + msg));
            ctx.fireChannelRead(msg);
          }
        };
    var channel = new EmbeddedChannel(tap, vanilla);
    try {
      assertTrue(channel.writeInbound("full-menu"));
      assertEquals("full-menu", channel.readInbound());
      assertNull(channel.readInbound());
      assertTrue(result.isEmpty());
      while (!clientQueue.isEmpty()) clientQueue.removeFirst().run();
      assertEquals(List.of("vanilla:full-menu", "receipt:full-menu"), result);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void unobservedPacketsStillReachVanillaWithoutSchedulingEvidence() {
    Deque<Runnable> clientQueue = new ArrayDeque<>();
    var channel = new EmbeddedChannel(new ReceiptTap(msg -> null, clientQueue::add));
    try {
      assertTrue(channel.writeInbound("chat"));
      assertEquals("chat", channel.readInbound());
      assertTrue(clientQueue.isEmpty());
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
