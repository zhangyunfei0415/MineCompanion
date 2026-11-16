package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.util.function.Consumer;

/** The expedition owns budgets, break gating and receipts while Baritone runs its mine process. */
final class BaritoneMiningJob extends Actions.Job {
  private final MiningExpedition expedition;
  private final BaritoneNavigation backend = new BaritoneNavigation();
  private final int before, wanted;
  private boolean started;
  private int idle;
  private InventoryMove prepare;

  BaritoneMiningJob(MiningExpedition expedition, int wanted, Consumer<String> done) {
    super(done);
    this.expedition = expedition;
    this.wanted = wanted;
    before = expedition.brokenCount();
  }

  void tick() {
    Actions.status = "Baritone采矿 · 当前批次已确认 " + (expedition.brokenCount() - before) + " / " + wanted;
    if (!started) {
      var mc = net.minecraft.client.Minecraft.getInstance();
      if (prepare == null) {
        int slot =
            MiningTools.choose(
                mc.level.getBlockState(expedition.target()),
                expedition.spec.toolPolicy(),
                expedition.spec.durability());
        if (slot < 0) {
          complete("没有满足本批条件的工具");
          return;
        }
        String fingerprint = WorldSense.fingerprint(mc.player.getInventory().getItem(slot));
        prepare = InventoryMove.ensure(s -> Actions.match(s, fingerprint));
      }
      int ready = prepare.tick();
      if (ready == 0) return;
      if (ready < 0) {
        complete(prepare.error);
        return;
      }
      mc.player.getInventory().selected = prepare.readySlot;
      started = true;
      if (!backend.startMining(
          expedition.survey.types(),
          expedition.spec.terrain().equals("modify"),
          expedition.spec.durability())) {
        finish("失败：[BARITONE_UNAVAILABLE] 采矿进程启动失败，请mining_status查看后端诊断");
        return;
      }
    }
    if (!expedition.withinWorkArea()) {
      interruptedBy("走出本次矿区边界，采矿后端已停止");
      return;
    }
    if (!expedition.lastGuard.isBlank()) {
      complete("采矿动作被本次边界/安全条件拦截，" + expedition.lastGuard);
      return;
    }
    if (expedition.brokenCount() - before >= wanted) {
      complete("本批已取得服务器挖除回执");
      return;
    }
    if (!backend.miningActive()) idle++;
    else idle = 0;
    if (++ticks > 1800 || idle > 40) complete("本批时间预算耗尽或采矿进程结束");
  }

  void interruptedBy(String why) {
    complete(why);
  }

  private void complete(String detail) {
    var facts = new JsonObject();
    int broken = expedition.brokenCount() - before;
    facts.addProperty("broken_verified", broken);
    facts.addProperty("backend", "baritone_mine_process");
    facts.addProperty("guard", expedition.lastGuard);
    finish(
        new ActionResult(
                broken >= wanted ? ActionResult.Status.verified : ActionResult.Status.partial,
                broken >= wanted ? ActionResult.Code.NONE : ActionResult.Code.NO_PATH,
                detail,
                facts)
            .json());
  }

  @Override
  void stop() {
    backend.stop();
    if (prepare != null) prepare.stop();
    super.stop();
  }
}
