package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** Read-only current-state checks. Unknown future menu/material state never becomes a promise. */
public final class CapabilityProbe {
  public static JsonObject inspect(JsonObject input) {
    var steps = input.getAsJsonArray("steps");
    if (steps == null || steps.isEmpty() || steps.size() > 16)
      throw new IllegalArgumentException("[ARGUMENT_REQUIRED] steps需要1..16项tool/args");
    var out = new JsonObject();
    var rows = new JsonArray();
    int blocking = -1;
    boolean unknown = false, human = false;
    var mc = Minecraft.getInstance();
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i).getAsJsonObject();
      String tool = Agent.str(step, "tool", "");
      var args = Agent.obj(step, "args");
      String denial = "";
      Boolean can = null;
      try {
        if (!Rules.TOOLS.contains(tool)) denial = "[UNSUPPORTED_TOOL] 当前构建没有该工具";
        else if (mc.player == null || mc.level == null) denial = "[WORLD_REQUIRED] 尚未进入世界";
        else if (!Agent.enabled && CapabilityCatalog.mutates(tool))
          denial = "[TAKEOVER_DISABLED] 游戏接管关闭";
        else if (Store.config.permission.equals("ASK") && CapabilityCatalog.mutates(tool))
          denial = "[APPROVAL_REQUIRED] 执行时仍需游戏内审批";
        else if (Store.config.permission.equals("LIMITED")) {
          denial =
              PermissionPolicy.denial(
                  tool,
                  new PermissionPolicy.Grants(
                      "LIMITED",
                      Store.config.allowContainers,
                      Store.config.allowGui,
                      Store.config.allowDrop,
                      Store.config.allowEntityUse));
          if (denial.isBlank())
            denial =
                Rules.limitedDenial(
                    tool, Store.config.allowMine, Store.config.allowPlace, Store.config.allowHunt);
          if (denial.isBlank()
              && ControlPolicy.needsInputPermission(tool)
              && !Store.config.allowInput) denial = "[POLICY_DENIED] 未授权通用输入";
        }
        if (denial.isBlank()) {
          String dependency = CapabilityCatalog.dependency(tool);
          if (!dependency.isBlank() && !net.minecraftforge.fml.ModList.get().isLoaded(dependency))
            denial = "[MISSING_COMPONENT] 缺少 " + dependency;
          else if (Set.of("break_block", "attack_block").contains(tool)) {
            var p = Actions.target(args);
            if (!mc.level.hasChunkAt(p)) denial = "[UNLOADED_TARGET] 目标尚未加载";
            else {
              var state = mc.level.getBlockState(p);
              denial =
                  BreakPolicy.denial(
                      BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                      state.hasBlockEntity(),
                      true,
                      args.has("confirm") && args.get("confirm").getAsBoolean(),
                      MiningJob.freshEmpty(p));
              if (denial.isBlank() && state.isAir()) denial = "[TARGET_ABSENT] 当前没有待拆方块";
            }
          } else if (tool.equals("write_sign")) {
            SignLines.normalize(args.getAsJsonArray("lines"));
            if (!SignEditor.active()) unknown = true;
          } else if (!CapabilityCatalog.mutates(tool)) can = true;
        }
      } catch (RuntimeException e) {
        denial = e.getMessage() == null ? "[ARGUMENT_INVALID] 参数不可解析" : e.getMessage();
      }
      var row = new JsonObject();
      row.addProperty("index", i);
      row.addProperty("tool", tool);
      if (!denial.isBlank()) {
        row.addProperty("can_do", false);
        ReasonCodes.describe(denial).entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
        if (blocking < 0) blocking = i;
        human |= row.get("requires_human").getAsBoolean();
      } else {
        if (can == null) {
          row.add("can_do", JsonNull.INSTANCE);
          unknown = true;
          row.addProperty("reason_code", "RUNTIME_CHECK_REQUIRED");
        } else row.addProperty("can_do", can);
        row.addProperty("note", "权限/类型预检不证明后续材料、路径、菜单和效果；前一步改变世界后需重新检查");
      }
      rows.add(row);
    }
    out.add("steps", rows);
    if (blocking >= 0) out.addProperty("feasible", false);
    else if (unknown) out.add("feasible", JsonNull.INSTANCE);
    else out.addProperty("feasible", true);
    out.addProperty("blocking_index", blocking);
    out.addProperty("requires_human", human);
    out.addProperty("read_only", true);
    out.addProperty("executes_actions", false);
    return out;
  }
}
