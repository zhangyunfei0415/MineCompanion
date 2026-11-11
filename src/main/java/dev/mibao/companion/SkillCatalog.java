package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/** Independent client-player procedures. No maid entity code or server capabilities. */
public final class SkillCatalog {
  public static final String VERSION = "8";

  public record Preset(
      String id,
      String name,
      String tool,
      String parameters,
      String prerequisites,
      String steps,
      String verification,
      String recovery) {}

  public static final List<Preset> ALL =
      List.of(
          new Preset(
              "transport",
              "定量搬运",
              "transport",
              "source, destination, item, count=32 (1..64)",
              "同维度两个不同的已命名普通箱子；通道可达；背包有空间；获得容器操作权限",
              "打开源箱并同步 → 取货并重开核对 → 走到交付箱 → 存货并重开核对",
              "取出与交付的实际数量必须一致；不把原有随身物品算作本次取货",
              "缺货/满箱/不同菜单/途中停止即结束；报告已取、已交付、待核实数量，重新观察后再下令"),
          new Preset(
              "collect",
              "收集掉落物",
              "collect_items",
              "item=*, mode=count/sweep, count=32 (1..256), radius=12 (2..24)",
              "生存/冒险模式；已加载且由客户端同步的物品实体，遮挡不代表未知；背包可容纳；拾取权限",
              "固定本次搜索中心 → 缓存候选与容量检查 → 选择实际安全站位 → 接近并稳定落地 → 等待真实拾取及服务器背包同步",
              "按物品ID+NBT分别核对服务器确认的背包增量；实体消失不能证明拾取；整堆拾取可超过目标",
              "目标消失/移动/不可达时最多跳过12个；无进展或90秒停止；不足目标报告部分完成"),
          new Preset(
              "farm",
              "成熟收割并补种",
              "harvest_replant",
              "crop=minecraft:wheat / carrots / potatoes / beetroots, count=8 (1..64), radius=8"
                  + " (2..12)",
              "生存模式；仅原版四种成熟作物、下方耕地；对应无NBT种子在随身背包，快捷栏有空位或可安全交换的普通材料；采集及放置权限；背包有空位",
              "分帧搜索成熟株 → 检查种子与安全站位 → 正常挖掘 → 等待服务器确认空气 → 正常使用种子 → 确认同种幼苗及种子消耗",
              "同时取得目标方块服务器更新与背包种子减少；记录收割/补种株数；产物拾取另用收集技能核对",
              "缺种子原地等待30秒、最多两次；未收割目标不可达时冷却换目标，每目标最多两次；补种结果不确定保留坐标并停止，最多一次有证据的补种重试"),
          new Preset(
              "mine",
              "安全采矿",
              "mine_resource",
              "block=minecraft:iron_ore 或 #forge:ores/iron, count=16 (1..64方块), radius=12 (2..24),"
                  + " delivery=可选当前世界交付箱",
              "生存模式；已加载且暴露的目标；现有通道可达；随身背包有正确工具，快捷栏可由安全工作槽准备；背包空位；采矿授权",
              "固定搜索中心 → 每帧最多256方块/2毫秒收集候选 → 检查流体/落沙/站位 → 正常寻路挖掘 → 等待服务器方块确认 → 每4块主动收集附近新掉落",
              "客户端与服务器都确认为空气才计数；背包增量另列，不将方块数量等同矿物产量",
              "12个不可达候选、单块15秒、总计5分钟或低血低饥即停；保留已确认数量；指定交付箱时可核实卸货后返回；不自动挖通道、挖脚下或处理流体"),
          new Preset(
              "hunt",
              "指定怪物清理",
              "hunt_mobs",
              "entity=hostile 或 zombie/skeleton/spider及普通变种, count=1 (1..8), radius=8 (2..16)",
              "生存模式；随身背包有近战属性且耐久足够的武器，快捷栏可由安全工作槽准备；足够生命饱食；可见目标；主动清怪授权",
              "先准备武器 → 固定区域锁定可见目标 → 寻找可见攻击站位 → 按冷却和伤害窗口近战 → 等待服务器死亡事件",
              "发出攻击不计数；仅对曾攻击的目标观察服务器死亡事件，UUID去重；不代表玩家击杀归属或战利品",
              "目标消失无死亡证据不计数并有界换目标；无伤害暂避，不重复空挥；24次攻击、单目标30秒、总计2分钟、越界或低血停止；不打玩家/Boss/其他生物"),
          new Preset(
              "craft",
              "普通配方制作",
              "craft_item",
              "item=产物ID, count=1 (1..64新增产物), source=可选当前世界补料箱",
              "生存/冒险；随身普通无NBT材料；快捷栏可自动准备；缺工作台需放置权限",
              "分帧查找工作台 → 有界递归计划 → 按需制作/放置工作台 → 逐批请求普通配方 → 同步检查材料 → 取出 → 退出",
              "每批材料消耗与产物背包指纹数量账本一致；配方批量可能多产",
              "128批/4096搜索上限；明确来源时先核实缺料、最多16次取料后重算；缺料/NBT歧义/菜单改变/同步超时即停，保留合成栏；检查点不自动重放"),
          new Preset(
              "smelt",
              "普通熔炉加工",
              "smelt_item",
              "item=输入ID（如minecraft:raw_iron）, count=1 (1..64输入物品)",
              "空原版普通熔炉；随身材料和同种煤或木炭；缺炉需圆石、木材及放置权限",
              "寻找/制作放置炉 → 打开并同步 → 按实际数量投输入与燃料 → 有界等待产物 → 取出并退出",
              "每次投料的背包与鼠标变化由服务器确认；最终产物数量与背包增量相符",
              "最多20分钟等候；满包/缺料/界面改变/不一致即停；炉内已投入材料可能继续加工，不自动再次投料"),
          new Preset(
              "workshop",
              "准备基础工坊",
              "prepare_workshop",
              "无参数",
              "随身木材/木板、圆石8个；可准备工作槽；安全可见放置位置；逐步权限",
              "分帧找工作台及炉 → 复用现有站点 → 制作缺少站点 → 正常主手放置 → 退出界面",
              "新放站点同时取得服务器方块更新和物品减少1；现有站点重新确认",
              "无可达位置/缺料即停；不拆现有方块，不自动采矿；中断后重新观察"),
          new Preset(
              "plant",
              "空耕地播种",
              "plant_crops",
              "crop,count,radius",
              "对应种子、空耕地、生存模式",
              "准备物品→可见站位→播种→核验",
              "种子减少1与幼苗更新",
              "缺料限时等待；不确定使用不重放"),
          new Preset(
              "fertilize",
              "骨粉催熟",
              "fertilize_crops",
              "crop,count,radius,max_uses",
              "骨粉与已加载作物",
              "准备骨粉→逐次使用→检查成熟度",
              "每次消耗与最终成熟分别核验",
              "最多256次，缺料等30秒"),
          new Preset(
              "farm_cycle",
              "播种催熟收获闭环",
              "farm_cycle",
              "crop,count,radius,source,plant,fertilize,harvest,collect",
              "种子、可选骨粉、普通材料箱",
              "可选取料→播种→可选催熟→收割补种→拾取",
              "各子工具核验；整体账本记录",
              "失败停后续，不重复投料"),
          new Preset(
              "wood",
              "备斧砍树并拾取",
              "chop_wood",
              "count=16 (1..64原木), radius=12 (2..24), delivery=可选当前世界交付箱",
              "生存；背包有斧子或能做木/石斧的普通材料；缺工作台需要放置授权；安全可达天然树干",
              "准备/按需制作斧子 → 检查土上树干与附近树叶 → 每4块正常挖除并等服务器确认 → 主动拾取附近新掉落 → 数量核对",
              "挖除和入包分别验收，装备耐久NBT改变不算新增；不凭空移除整树",
              "缺材料/高处不可达/满包/领地限制即停；不自动搭高、攀树或砍建筑木柱；明确配置交付箱后可有界卸货再恢复"));

  public static Preset get(String id) {
    return ALL.stream()
        .filter(p -> p.id.equals(id) || p.name.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知预制技能"));
  }

  public static JsonObject catalog() {
    JsonObject o = new JsonObject();
    o.addProperty("version", VERSION);
    JsonArray a = new JsonArray();
    for (var p : ALL) {
      JsonObject j = new JsonObject();
      j.addProperty("id", p.id);
      j.addProperty("name", p.name);
      j.addProperty("tool", p.tool);
      j.addProperty("parameters", p.parameters);
      j.addProperty("prerequisites", p.prerequisites);
      j.addProperty("steps", p.steps);
      j.addProperty("verification", p.verification);
      j.addProperty("recovery", p.recovery);
      a.add(j);
    }
    o.add("presets", a);
    return o;
  }

  public static int bounded(JsonObject a, String key, int fallback, int min, int max) {
    int n;
    try {
      n = a.has(key) ? a.get(key).getAsBigDecimal().intValueExact() : fallback;
    } catch (Exception e) {
      throw new IllegalArgumentException(key + " 必须是范围内的整数");
    }
    if (n < min || n > max) throw new IllegalArgumentException(key + " 必须在 " + min + ".." + max);
    return n;
  }

  public static String required(JsonObject a, String key) {
    String v = a.has(key) ? a.get(key).getAsString().trim() : "";
    if (v.isBlank()) throw new IllegalArgumentException("缺少参数：" + key);
    return v;
  }
}
