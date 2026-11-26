package dev.mibao.companion;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

/**
 * Dedicated「蓝图与建造」Ledger page inside WorkbenchScreen for discovering .nbt/.litematic files,
 * inspecting BOM shortages against bag + WorldMemory chests, previewing transforms, and launching
 * or resuming BlueprintBuildJob.
 */
final class WorkbenchBlueprintView {
  private final WorkbenchScreen ui;
  private String selectedBlueprint = "";
  private int rotation;
  private String mirror = "none";

  WorkbenchBlueprintView(WorkbenchScreen ui) {
    this.ui = ui;
  }

  void render() {
    UiTheme t = ui.theme();
    var mc = Minecraft.getInstance();
    var cp = BlueprintBuildJob.statusJson();
    String cpState = cp.has("state") ? cp.get("state").getAsString() : "none";
    boolean running = cp.has("active_now") && cp.get("active_now").getAsBoolean();

    // 1. Active / Checkpoint Status Card
    String badgeText =
        running
            ? "施工中"
            : cpState.equals("complete")
                ? "已完工"
                : cpState.equals("none") ? "待命" : "断点：" + cpState;
    int badgeColor =
        running
            ? t.success()
            : cpState.equals("complete")
                ? t.accent()
                : cpState.equals("none") ? t.textMuted() : t.warning();

    ui.card(
        "当前蓝图施工状态",
        badgeText,
        badgeColor,
        () -> {
          if (cpState.equals("none")) {
            ui.mutedText("当前世界暂无进行中或暂停的蓝图施工任务；请在下方选择 .nbt 或 .litematic 蓝图。");
          } else {
            String bpName =
                cp.has("file_name") && !cp.get("file_name").getAsString().isBlank()
                    ? cp.get("file_name").getAsString()
                    : (cp.has("name") ? cp.get("name").getAsString() : "");
            int cursor = cp.has("cursor") ? cp.get("cursor").getAsInt() : 0;
            int total = cp.has("total_steps") ? cp.get("total_steps").getAsInt() : 0;
            int placed = cp.has("placed") ? cp.get("placed").getAsInt() : 0;
            int matched = cp.has("already_matched") ? cp.get("already_matched").getAsInt() : 0;
            int mismatch = cp.has("state_mismatch") ? cp.get("state_mismatch").getAsInt() : 0;
            int miss = cp.has("skipped_missing_item") ? cp.get("skipped_missing_item").getAsInt() : 0;
            int unsup = cp.has("skipped_unsupported") ? cp.get("skipped_unsupported").getAsInt() : 0;
            int failed = cp.has("failed") ? cp.get("failed").getAsInt() : 0;
            int scPlaced = cp.has("scaffolds_placed") ? cp.get("scaffolds_placed").getAsInt() : 0;
            int scRemoved = cp.has("scaffolds_removed") ? cp.get("scaffolds_removed").getAsInt() : 0;
            int scRem = cp.has("scaffolds_remaining") ? cp.get("scaffolds_remaining").getAsInt() : 0;
            ui.text(
                "蓝图 · "
                    + bpName
                    + " @ ("
                    + cp.get("anchor_x").getAsInt()
                    + ", "
                    + cp.get("anchor_y").getAsInt()
                    + ", "
                    + cp.get("anchor_z").getAsInt()
                    + ")");
            ui.mutedText(
                "进度 "
                    + cursor
                    + "/"
                    + total
                    + " · 新放 "
                    + placed
                    + " · 已有 "
                    + matched
                    + (mismatch > 0 ? " · 属性不符 " + mismatch : "")
                    + " · 缺料 "
                    + miss
                    + " · 跳过 "
                    + unsup
                    + (failed > 0 ? " · 失败 " + failed : "")
                    + ((scPlaced > 0 || scRem > 0)
                        ? " · 脚手架 搭" + scPlaced + "/拆" + scRemoved + "/余" + scRem
                        : ""));
            if (running) {
              ui.row(
                  "暂停施工并保存断点",
                  () -> {
                    BlueprintBuildJob.pauseActive();
                    ui.setStatus("已暂停当前蓝图施工并保存断点");
                    ui.rebuild();
                  },
                  "中止并清除断点",
                  () -> {
                    BlueprintBuildJob.abortActive();
                    ui.setStatus("已中止并清除当前世界蓝图断点");
                    ui.rebuild();
                  });
            } else {
              if (!cpState.equals("complete")) {
                ui.row(
                    "断点续建此蓝图",
                    () -> {
                      ui.onClose();
                      if (!Agent.enabled) Agent.start();
                      Agent.execute("blueprint_status", actionObj("resume"), Companion::notifyUser);
                    },
                    "清除旧断点记录",
                    () -> {
                      BlueprintBuildJob.clearWorldCheckpoint();
                      ui.setStatus("已清除旧蓝图断点记录");
                      ui.rebuild();
                    });
              }
              if (scRem > 0) {
                ui.button(
                    "LIFO 安全清理脚手架 (" + scRem + " 格)",
                    () -> {
                      ui.onClose();
                      if (!Agent.enabled) Agent.start();
                      Agent.execute(
                          "blueprint_status", actionObj("cleanup_scaffold"), Companion::notifyUser);
                    });
              }
            }
          }
        });

    // 2. Discover List or Selected Detail + BOM + Anchor Form
    if (selectedBlueprint.isBlank()) {
      renderDiscoveryCard(mc, t);
    } else {
      renderSelectedDetail(mc, t);
    }

    // 3. Secondary Engineering Shortcuts
    ui.collapsibleCard(
        "bp/platform",
        "平台搭建与目录说明",
        "",
        0,
        false,
        () -> {
          ui.mutedText(
              "自动扫描以下目录中的 .nbt（原版/Create 蓝图）与 .litematic（Litematica）：\n"
                  + "schematics/ · config/litematica/ · schematics/litematica/ · config/logistics-companion/blueprints/");
          ui.button(
              "查看最近平台施工状态 (PlatformJob)",
              () -> {
                if (mc.level == null) ui.setStatus("请先进入游戏世界再查看平台状态");
                else ui.openNotice(Store.GSON.toJson(PlatformJob.status()));
                ui.rebuild();
              });
        });
  }

  private void renderDiscoveryCard(Minecraft mc, UiTheme t) {
    var listJson = BlueprintLibrary.listJson();
    var arr = listJson.getAsJsonArray("blueprints");
    ui.card(
        "可用蓝图列表 (.nbt / .litematic)",
        arr.size() + " 份",
        arr.isEmpty() ? t.textMuted() : t.accent(),
        () -> {
          ui.row(
              "刷新蓝图列表",
              () -> {
                ui.setStatus("已重新扫描蓝图目录，共发现 " + BlueprintLibrary.discover().size() + " 份");
                ui.rebuild();
              },
              "打开 schematics 目录",
              () -> openOrCreateDir(mc.gameDirectory.toPath().resolve("schematics")));
          ui.button(
              "打开 config/litematica 目录",
              () -> openOrCreateDir(mc.gameDirectory.toPath().resolve("config/litematica")));

          if (arr.isEmpty()) {
            ui.mutedText("未扫描到 .nbt 或 .litematic 文件；放入上方目录后点击刷新即可。");
          } else {
            for (var el : arr) {
              var o = el.getAsJsonObject();
              String name = o.get("name").getAsString();
              if (o.has("error")) {
                ui.coloredText("损坏蓝图 · " + name + "：" + o.get("error").getAsString(), t.danger());
                continue;
              }
              String fmt = o.get("format").getAsString().toUpperCase(Locale.ROOT);
              String dim =
                  o.get("size_x").getAsInt()
                      + "×"
                      + o.get("size_y").getAsInt()
                      + "×"
                      + o.get("size_z").getAsInt();
              int count = o.get("non_air_blocks").getAsInt();
              ui.button(
                      name + "（" + dim + " · " + count + " 格）",
                      () -> {
                        selectedBlueprint = name;
                        prefillAnchorIfBlank(mc);
                        ui.rebuild();
                      })
                  .badge(fmt, fmt.equals("LITEMATIC") ? t.accentAlt() : t.accent());
            }
          }
        });
  }

  private void renderSelectedDetail(Minecraft mc, UiTheme t) {
    ui.button(
        "‹ 返回蓝图列表",
        () -> {
          selectedBlueprint = "";
          ui.rebuild();
        });

    prefillAnchorIfBlank(mc);
    JsonObject inspect;
    try {
      inspect = BlueprintLibrary.inspectJson(selectedBlueprint, rotation, mirror);
    } catch (IOException | RuntimeException e) {
      ui.alertCard(
          "蓝图读取失败 · " + selectedBlueprint,
          "错误",
          t.danger(),
          () -> ui.text(String.valueOf(e.getMessage())));
      return;
    }

    int missing = inspect.get("total_missing_items").getAsInt();
    var materials = inspect.getAsJsonArray("materials");
    String rawDisplay = inspect.has("display_name") ? inspect.get("display_name").getAsString() : "";
    String fileName = inspect.has("file_name") ? inspect.get("file_name").getAsString() : selectedBlueprint;
    String cardTitle =
        rawDisplay.isBlank() || rawDisplay.equalsIgnoreCase("Unnamed")
            ? fileName
            : rawDisplay + " (" + fileName + ")";
    ui.card(
        cardTitle,
        missing == 0 ? "材料齐备" : "缺 " + missing + " 件",
        missing == 0 ? t.success() : t.warning(),
        () -> {
          ui.text(
              "格式 "
                  + inspect.get("format").getAsString().toUpperCase(Locale.ROOT)
                  + " · 尺寸 "
                  + inspect.get("size_x").getAsInt()
                  + "×"
                  + inspect.get("size_y").getAsInt()
                  + "×"
                  + inspect.get("size_z").getAsInt()
                  + " · 非空气方块 "
                  + inspect.get("non_air_blocks").getAsInt()
                  + " 格");
          if (inspect.has("scaffold_estimate")) {
            var est = inspect.getAsJsonObject("scaffold_estimate");
            if (est.has("needs_scaffold") && est.get("needs_scaffold").getAsBoolean()) {
              ui.mutedText(
                  "搭高估算 · 需脚手架 "
                      + est.get("recommended_scaffolding").getAsInt()
                      + " 或廉价垫块约 "
                      + est.get("recommended_solid_blocks").getAsInt()
                      + "（完工自动 LIFO 回收）");
            }
          }

          ui.mutedText("旋转角度 (顺时针)：");
          ui.segmentRow(
              List.of(
                  rotOption("0°", 0),
                  rotOption("90°", 90),
                  rotOption("180°", 180),
                  rotOption("270°", 270)));

          ui.mutedText("镜像方式：");
          ui.segmentRow(
              List.of(
                  mirrorOption("无镜像", "none"),
                  mirrorOption("左右镜像", "left_right"),
                  mirrorOption("前后镜像", "front_back")));

          int shown = 0;
          for (var el : materials) {
            if (++shown > 14) break;
            var r = el.getAsJsonObject();
            int m = r.get("missing").getAsInt();
            String rowText =
                r.get("item").getAsString()
                    + " · 需 "
                    + r.get("need").getAsInt()
                    + " | 包 "
                    + r.get("in_bag").getAsInt()
                    + " | 箱 "
                    + r.get("in_base_chests").getAsInt()
                    + (m > 0 ? " | 缺 " + m : "");
            if (m > 0) ui.coloredText(rowText, t.warning());
            else ui.mutedText(rowText);
          }
          if (materials.size() > 14) {
            ui.button(
                "查看完整 BOM 清单（共 " + materials.size() + " 种材料）",
                () -> ui.openNotice(Store.GSON.toJson(inspect)));
          }
        });

    ui.card(
        "锚点定位与分层施工",
        "Y 逐层施工",
        t.accentAlt(),
        () -> {
          ui.button(
              "填入当前脚下坐标 (+1, 0, +1)",
              () -> {
                if (mc.player == null) throw new IllegalArgumentException("请先进入世界");
                var p = mc.player.blockPosition().offset(1, 0, 1);
                ui.putDraft("bp/x", Integer.toString(p.getX()));
                ui.putDraft("bp/y", Integer.toString(p.getY()));
                ui.putDraft("bp/z", Integer.toString(p.getZ()));
                ui.rebuild();
              });
          ui.field("bp/x", "锚点 X 坐标", "0", v -> {});
          ui.field("bp/y", "锚点 Y 坐标（底层脚部高度）", "64", v -> {});
          ui.field("bp/z", "锚点 Z 坐标", "0", v -> {});
          ui.field("bp/ymin", "起始相对层 y_min（默认 0）", "0", v -> {});
          ui.field("bp/ymax", "结束相对层 y_max（-1 = 全部层）", "-1", v -> {});

          ui.row(
              "预检占用与缺料 (Dry Run)",
              () -> {
                ui.saveDrafts();
                if (mc.level == null) throw new IllegalArgumentException("请先进入游戏世界");
                int ax = parseInt("bp/x", "锚点 X");
                int ay = parseInt("bp/y", "锚点 Y");
                int az = parseInt("bp/z", "锚点 Z");
                int yMin = parseInt("bp/ymin", "y_min");
                int yMax = parseInt("bp/ymax", "y_max");
                try {
                  String report =
                      BlueprintBuildJob.dryRunReport(
                          selectedBlueprint, ax, ay, az, rotation, mirror, yMin, yMax);
                  ui.openNotice(report);
                } catch (IOException e) {
                  throw new IllegalArgumentException(e.getMessage());
                }
              },
              "开始分层蓝图施工",
              () -> {
                ui.saveDrafts();
                if (mc.level == null) throw new IllegalArgumentException("请先进入游戏世界");
                var args = new JsonObject();
                args.addProperty("name", selectedBlueprint);
                args.addProperty("x", parseInt("bp/x", "锚点 X"));
                args.addProperty("y", parseInt("bp/y", "锚点 Y"));
                args.addProperty("z", parseInt("bp/z", "锚点 Z"));
                args.addProperty("rotation", rotation);
                args.addProperty("mirror", mirror);
                args.addProperty("y_min", parseInt("bp/ymin", "y_min"));
                args.addProperty("y_max", parseInt("bp/ymax", "y_max"));
                ui.onClose();
                if (!Agent.enabled) Agent.start();
                Agent.execute("blueprint_build", args, Companion::notifyUser);
              });
        });
  }

  private void prefillAnchorIfBlank(Minecraft mc) {
    if (mc.player == null) return;
    if (ui.value("bp/x", "").isBlank()
        && ui.value("bp/y", "").isBlank()
        && ui.value("bp/z", "").isBlank()) {
      var p = mc.player.blockPosition().offset(1, 0, 1);
      ui.putDraft("bp/x", Integer.toString(p.getX()));
      ui.putDraft("bp/y", Integer.toString(p.getY()));
      ui.putDraft("bp/z", Integer.toString(p.getZ()));
    }
  }

  private int parseInt(String key, String label) {
    try {
      return Integer.parseInt(ui.value(key, "0").trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(label + " 必须是整数");
    }
  }

  private WorkbenchScreen.SegmentOption rotOption(String label, int deg) {
    return new WorkbenchScreen.SegmentOption(
        label,
        rotation == deg,
        () -> {
          rotation = deg;
          ui.rebuild();
        });
  }

  private WorkbenchScreen.SegmentOption mirrorOption(String label, String mode) {
    return new WorkbenchScreen.SegmentOption(
        label,
        mirror.equals(mode),
        () -> {
          mirror = mode;
          ui.rebuild();
        });
  }

  private void openOrCreateDir(Path dir) {
    try {
      Files.createDirectories(dir);
      Util.getPlatform().openFile(dir.toFile());
    } catch (IOException e) {
      throw new IllegalArgumentException("无法创建或打开目录：" + dir);
    }
  }

  private static JsonObject actionObj(String action) {
    var o = new JsonObject();
    o.addProperty("action", action);
    return o;
  }
}
