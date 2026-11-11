package dev.mibao.companion;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import net.minecraft.client.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;

@Mod(Companion.ID)
public final class Companion {
  public static final String ID = "logistics_companion";
  public static final Deque<String> logs = new ArrayDeque<>();
  public static final KeyMapping PANEL =
      new KeyMapping(
          "key.logistics_companion.panel",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F8,
          "key.categories.logistics_companion");
  public static final KeyMapping STOP =
      new KeyMapping(
          "key.logistics_companion.stop",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F9,
          "key.categories.logistics_companion");
  public static final KeyMapping HUD =
      new KeyMapping(
          "key.logistics_companion.hud",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F7,
          "key.categories.logistics_companion");
  public static final KeyMapping WATCH =
      new KeyMapping(
          "key.logistics_companion.watch",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F6,
          "key.categories.logistics_companion");
  public static final KeyMapping DIALOGUE =
      new KeyMapping(
          "key.logistics_companion.dialogue",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_HOME,
          "key.categories.logistics_companion");
  public static final KeyMapping COOP =
      new KeyMapping(
          "key.logistics_companion.cooperative",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F10,
          "key.categories.logistics_companion");
  private boolean connected;
  public static final KeyMapping QUICK_START =
      new KeyMapping(
          "key.logistics_companion.quick_start",
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_F4,
          "key.categories.logistics_companion");
  private int ticks;
  private final ManualUseLatch manualUseLatch = new ManualUseLatch();

  public Companion() {
    if (FMLEnvironment.dist != Dist.CLIENT) return;
    Store.init();
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::keys);
    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::overlays);
    MinecraftForge.EVENT_BUS.register(this);
  }

  private void keys(RegisterKeyMappingsEvent e) {
    e.register(PANEL);
    e.register(STOP);
    e.register(HUD);
    e.register(WATCH);
    e.register(DIALOGUE);
    e.register(COOP);
    e.register(QUICK_START);
  }

  @SubscribeEvent
  public void recipesUpdated(net.minecraftforge.client.event.RecipesUpdatedEvent e) {
    RecipeBridge.changed();
  }

  private void overlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent e) {
    e.registerAboveAll(
        "companion_status",
        (gui, g, partial, w, h) -> {
          var screen = Minecraft.getInstance().screen;
          HarnessController.frame(screen);
          Hud.render(g, w, h);
          SceneOverlay.hud(g);
          if (!(screen
              instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
            LocalDialogue.render(g);
            WatchAvatar.render(g, w, h);
          }
        });
  }

  public static void log(String s) {
    String clean = Rules.clean(s, 1200);
    logs.addLast(clean);
    while (logs.size() > 160) logs.removeFirst();
    Store.journal(clean);
  }

  public static void notifyUser(String s) {
    Messages.notice(s);
  }

  @SubscribeEvent
  public void tick(TickEvent.ClientTickEvent e) {
    if (e.phase == TickEvent.Phase.START) {
      if (Actions.job instanceof PlatformJob building) building.guardEdge();
      AgentPointer.tick();
      var mc = Minecraft.getInstance();
      var use = mc.options.keyUse.getKey();
      if (use.getType() == InputConstants.Type.KEYSYM) {
        if (InputConstants.isKeyDown(mc.getWindow().getWindow(), use.getValue()))
          manualUseLatch.press();
        else manualUseLatch.release();
      }
      PlayerControls.guard();
      return;
    }
    if (e.phase != TickEvent.Phase.END) return;
    Minecraft mc = Minecraft.getInstance();
    while (PANEL.consumeClick()) mc.setScreen(new WorkbenchScreen());
    while (QUICK_START.consumeClick()) WatchMode.startWatching();
    while (STOP.consumeClick()) Agent.stop("紧急停止");
    while (WATCH.consumeClick()) WatchMode.toggle();
    while (DIALOGUE.consumeClick()) LocalDialogue.toggle();
    while (COOP.consumeClick()) {
      if (!Agent.enabled) Agent.start();
      Cooperation.set(!Cooperation.active());
    }
    while (HUD.consumeClick()) {
      Store.config.hud = !Store.config.hud;
      Store.save();
    }
    boolean now = mc.player != null && mc.level != null && mc.getConnection() != null;
    if (now && !connected) {
      Store.connect();
      CommunityTasks.reconnect();
      Perception.reset();
      WorldSense.reset();
      DynamicScene.reset();
      ClientEvidence.reset();
      VisualAssist.reset();
      StatusLink.reset();
      Hud.FEED.clear();
      LocalDialogue.reset();
      CompanionIdentity.restoreDialogue();
      Messages.reset();
      RestockTask.latest = null;
      Agent.stop("已连接。请在 " + PANEL.getTranslatedKeyMessage().getString() + " 面板明确开启 AI。");
      if (Store.data.checkpoints != null
          && Store.data.checkpoints.values().stream()
              .anyMatch(r -> !Agent.str(r, "phase", "").equals("complete")))
        notifyUser("上次世界作业留有检查点，请在生产工坊查看恢复记录并重新观察；不会自动重复取货/投料。");
    }
    if (!now && connected) {
      Agent.stop("游戏断开，休息");
      Store.disconnect();
      Perception.reset();
      WorldSense.reset();
      DynamicScene.reset();
      ClientEvidence.reset();
      VisualAssist.reset();
      StatusLink.reset();
      Messages.reset();
      RestockTask.latest = null;
    }
    connected = now;
    if (now) {
      if (Agent.enabled && mc.player.isDeadOrDying()) Agent.stop("角色死亡，已停止并交还控制");
      if ((WatchMode.active() || LocalDialogue.editing) && mc.mouseHandler.isMouseGrabbed())
        mc.mouseHandler.releaseMouse();
      Cooperation.poll();
      HarnessController.tick();
      ClientEvidence.tick();
      MenuContext.tick();
      WorldSense.tick();
      MenuContext.refreshMemory();
      StreamProgress.tick();
      LocalExecutor.tick();
      InventoryMemory.tick();
      VisualAssist.tick();
      Messages.tick();
      StatusLink.tick();
      QuestBookBridge.tick();
      if (++ticks % 600 == 0) Store.save();
    }
  }

  @SubscribeEvent
  public void physicalMouse(net.minecraftforge.client.event.InputEvent.MouseButton.Pre e) {
    if (!WatchMode.blockButtons()) AgentPointer.intervene();
    var use = Minecraft.getInstance().options.keyUse.getKey();
    if (use.getType() == InputConstants.Type.MOUSE && use.getValue() == e.getButton()) {
      if (e.getAction() == GLFW.GLFW_RELEASE) manualUseLatch.release();
      else if (e.getAction() == GLFW.GLFW_PRESS) manualUseLatch.press();
    }
    if (e.getAction() == GLFW.GLFW_PRESS) Cooperation.human("人工鼠标操作", false);
    if (LocalDialogue.mouse(e.getButton(), e.getAction()) || WatchMode.blockButtons())
      e.setCanceled(true);
  }

  @SubscribeEvent
  public void physicalScroll(net.minecraftforge.client.event.InputEvent.MouseScrollingEvent e) {
    if (!WatchMode.blockButtons()) AgentPointer.intervene();
    Cooperation.human("人工滚轮操作", false);
    if (LocalDialogue.scroll(e.getScrollDelta()) || WatchMode.blockButtons()) e.setCanceled(true);
  }

  @SubscribeEvent
  public void overlayOnMenu(net.minecraftforge.client.event.ScreenEvent.Render.Post e) {
    AgentPointer.render(e.getGuiGraphics());
    HarnessController.frame(e.getScreen());
    if (e.getScreen()
        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
      if (Store.config.processDetailed)
        Hud.render(e.getGuiGraphics(), e.getScreen().width, e.getScreen().height);
      LocalDialogue.render(e.getGuiGraphics());
      WatchAvatar.render(e.getGuiGraphics(), e.getScreen().width, e.getScreen().height);
    }
  }

  @SubscribeEvent
  public void debugScene(net.minecraftforge.client.event.RenderLevelStageEvent e) {
    SceneOverlay.render(e);
  }

  @SubscribeEvent
  public void manualUse(
      net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered e) {
    var mc = Minecraft.getInstance();
    boolean bucket =
        mc.player != null
            && (mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BucketItem
                || mc.player.getOffhandItem().getItem()
                    instanceof net.minecraft.world.item.BucketItem);
    if (e.isUseItem() && !Agent.enabled && bucket && !manualUseLatch.allow()) {
      e.setCanceled(true);
      e.setSwingHand(false);
      return;
    }
    if (e.isUseItem()
        && !Agent.enabled
        && !bucket
        && mc.player != null
        && !mc.player.isShiftKeyDown()
        && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
      var block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
      boolean once =
          mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BucketItem
              || mc.player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BucketItem
              || block instanceof net.minecraft.world.level.block.DoorBlock
              || block instanceof net.minecraft.world.level.block.TrapDoorBlock
              || block instanceof net.minecraft.world.level.block.FenceGateBlock
              || block instanceof net.minecraft.world.level.block.AbstractChestBlock
              || block instanceof net.minecraft.world.level.block.BarrelBlock
              || block instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
              || block instanceof net.minecraft.world.level.block.CraftingTableBlock;
      if (once && !manualUseLatch.allow()) {
        e.setCanceled(true);
        e.setSwingHand(false);
        return;
      }
    }
    VisualAssist.manual(e.isUseItem() ? "玩家使用物品/方块" : "玩家交互");
    if (Agent.enabled && e.isUseItem()) MenuContext.reset();
    else if (e.isUseItem()
        && Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.BlockHitResult h
        && h.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
      MenuContext.expect(h.getBlockPos());
  }

  @SubscribeEvent
  public void manualMenuClick(
      net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre e) {
    if (Cooperation.active()
        && e.getScreen()
            instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
      Cooperation.human("人工接手物品界面", true);
    else if (!WatchMode.active()
        && Agent.enabled
        && (Agent.routine != null
            || Actions.job != null
            || Agent.production != null
            || Agent.session != null)
        && e.getScreen()
            instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
      Agent.stop("玩家正在操作物品界面，已交还控制权");
    if (e.getScreen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)
      VisualAssist.manual("玩家菜单点击；原始坐标不用于重放");
  }

  @SubscribeEvent
  public void stopInsideScreen(net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre e) {
    // Vanilla does not click ordinary KeyMappings while a container/config screen is open.
    // Handle the user's rebound stop key before that screen consumes it.
    if ((Agent.enabled || Actions.job != null) && STOP.matches(e.getKeyCode(), e.getScanCode())) {
      while (STOP.consumeClick()) {}
      Agent.stop("界面内紧急停止");
      e.setCanceled(true);
    }
  }

  @SubscribeEvent
  public void chat(ClientChatReceivedEvent e) {
    if (Minecraft.getInstance().player == null) return;
    if (e instanceof ClientChatReceivedEvent.System) CommandJob.feedback(e.getMessage());
    String text = e.getMessage().getString();
    String marker = "@" + Store.config.name;
    String extracted = ChatCommand.extract(text, Store.config.name);
    if (extracted.isBlank()) return;
    if (e.getSender() == null || Minecraft.getInstance().getConnection() == null) return;
    var info = Minecraft.getInstance().getConnection().getPlayerInfo(e.getSender());
    if (info == null) return;
    String sender = info.getProfile().getName();
    if (sender.equalsIgnoreCase(Minecraft.getInstance().player.getGameProfile().getName())
        && Messages.isOwnReply(text)) return;
    String msg = extracted;
    if (msg.isBlank()) return;
    try {
      CommunityTasks.receive(e.getSender().toString(), sender, msg);
    } catch (Exception failure) {
      Messages.teamReport(
          sender, "接单未执行：" + Rules.clean(failure.getMessage(), 180), Store.config.teamReport);
    }
  }
}
