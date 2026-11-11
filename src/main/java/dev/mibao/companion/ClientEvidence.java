package dev.mibao.companion;

import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;

/** Passive receipt tap on Forge's existing connection. Does not send or change packets. */
public final class ClientEvidence {
  private static final String NAME = "logistics_companion_evidence";
  private static ClientPacketListener attached;
  private static boolean warned;

  public static void reset() {
    attached = null;
    warned = false;
    PlayerEvidence.reset();
    CombatEvidence.reset();
    InventoryEvidence.reset();
    EntitySpawnEvidence.reset();
    InventoryMemory.reset();
    MachineObservation.reset();
    GuiContext.invalidate();
  }

  public static boolean attached() {
    return attached != null && attached.getConnection().channel().pipeline().get(NAME) != null;
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    var listener = mc.getConnection();
    if (listener == null) return;
    if (attached == listener && attached()) return;
    try {
      var pipeline = listener.getConnection().channel().pipeline();
      if (pipeline.get(NAME) == null)
        pipeline.addBefore(
            "packet_handler",
            NAME,
            new ReceiptTap(
                msg -> {
                  // Copy packet stacks before vanilla can reuse them. No world or menu reads on
                  // Netty.
                  Runnable receipt = null;
                  if (msg instanceof ClientboundContainerSetContentPacket p) {
                    var stacks = p.getItems().stream().map(s -> s.copy()).toList();
                    var cursor = p.getCarriedItem().copy();
                    receipt =
                        () -> {
                          MenuContext.content(p.getContainerId(), p.getStateId(), stacks, cursor);
                          PlayerEvidence.content(p.getContainerId(), stacks);
                          if (p.getContainerId()
                              == Minecraft.getInstance().player.containerMenu.containerId)
                            PlayerEvidence.cursor(cursor);
                          InventoryEvidence.content(p.getContainerId(), stacks, cursor);
                        };
                  } else if (msg instanceof ClientboundContainerSetSlotPacket p) {
                    var stack = p.getItem().copy();
                    receipt =
                        () -> {
                          MenuContext.slot(p.getContainerId(), p.getStateId(), p.getSlot(), stack);
                          PlayerEvidence.slot(p.getContainerId(), p.getSlot(), stack);
                        };
                  } else if (msg instanceof ClientboundAddEntityPacket p) {
                    receipt = () -> EntitySpawnEvidence.record(p);
                  } else if (msg instanceof ClientboundSetHealthPacket p) {
                    receipt = () -> InventoryEvidence.health(p.getFood());
                  } else if (msg instanceof ClientboundDamageEventPacket p) {
                    receipt = () -> CombatEvidence.damage(p);
                  } else if (msg instanceof ClientboundEntityEventPacket p) {
                    receipt = () -> CombatEvidence.event(p);
                  } else if (msg instanceof ClientboundBlockEntityDataPacket p) {
                    var pos = p.getPos().immutable();
                    receipt = () -> MachineObservation.received(pos);
                  } else if (msg instanceof ClientboundBlockUpdatePacket p) {
                    receipt =
                        () -> {
                          WorldSense.blockChanged(p.getPos(), p.getBlockState());
                          PlayerEvidence.block(p.getPos(), p.getBlockState());
                        };
                  } else if (msg instanceof ClientboundSectionBlocksUpdatePacket p) {
                    receipt =
                        () ->
                            p.runUpdates(
                                (pos, state) -> {
                                  WorldSense.blockChanged(pos, state);
                                  PlayerEvidence.block(pos, state);
                                });
                  }
                  if (receipt == null) return null;
                  Runnable task = receipt;
                  return () -> {
                    if (mc.getConnection() == listener && mc.player != null && mc.level != null)
                      task.run();
                  };
                },
                mc::execute));
      attached = listener;
    } catch (Exception e) {
      if (!warned) {
        warned = true;
        Companion.notifyUser("无法接入客户端同步证据；自动容器操作将等待同步后超时，请反馈日志。" + e.getClass().getSimpleName());
      }
    }
  }
}
