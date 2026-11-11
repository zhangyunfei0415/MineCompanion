package dev.mibao.companion;

import com.google.gson.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class Store {
  public static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  public static final Path ROOT =
      Minecraft.getInstance().gameDirectory.toPath().resolve("config/logistics-companion");
  public static Config config = new Config();
  public static Data data = new Data();
  private static final Map<String, Skill> sharedSkills = new LinkedHashMap<>();
  private static boolean libraryReadable = true;
  private static boolean legacySkillsRead;
  private static boolean worldReadable = true;
  private static String worldKey = "disconnected";
  private static FileChannel controlChannel;
  private static FileLock controlLock;

  public static boolean hasControl() {
    return controlLock != null && controlLock.isValid();
  }

  public static boolean claimControl() {
    if (controlLock != null && controlLock.isValid()) return true;
    try {
      Files.createDirectories(ROOT);
      controlChannel =
          FileChannel.open(
              ROOT.resolve("controller.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      controlLock = controlChannel.tryLock();
      if (controlLock == null) {
        controlChannel.close();
        controlChannel = null;
        return false;
      }
      return true;
    } catch (Exception e) {
      try {
        if (controlChannel != null) controlChannel.close();
      } catch (Exception ignored) {
      }
      controlChannel = null;
      return false;
    }
  }

  public static class Config {
    public boolean huntResources = true;
    public int harnessLeaseSeconds = 3600;
    public boolean autoOpenDoors = true;
    public boolean navigationBreak = false, navigationPlace = false;
    public boolean apiStreaming = true, processDetailed = false;
    public boolean craftGestures = true;
    public String controlMode = "AI", cooperativeResume = "AUTO";
    public String name = "小勤",
        owner = "",
        endpoint = "https://api.deepseek.com",
        model = "deepseek-flash",
        apiKey = "";
    public String goal = "优先熟悉基地、记住仓库位置，学习可行的后勤补给工作。不要主动去危险地点。";
    public String permission = "ASK", theme = "深海", trusted = "";
    public String teamReport = "BRIEF";
    public boolean playerAutonomy = false, nearbySupplyPickup = false;
    public boolean compactToolMenu = false;
    public boolean compactObservation = true;
    public int contextChars = 80000;
    public String planningEffort = "MEDIUM";
    public boolean nativeReasoning = false, adaptiveVerification = true;
    public String executionStyle = "FLUENT";
    public String libraryFolder = "";
    public java.util.List<String> disabledPackages = new java.util.ArrayList<>();
    public boolean allowContainers = true,
        allowGui = true,
        allowDrop = false,
        allowEntityUse = true;
    public String visionMode = "";
    public String allowedKeys = "";
    public boolean fullGameControl = false;
    public boolean allowChatTyping = false, allowChatSubmit = false;
    public boolean agentPointerVisible = true;
    public boolean allowInput = false,
        backgroundInput = true,
        localDialogue = true,
        harnessImages = false;
    public String driver = "API", avatar = "DEEPSEEK", avatarStyle = "MOE";
    public String mcpToken = UUID.randomUUID().toString();
    public boolean vision = true,
        web = true,
        publicReply = false,
        allowMine = false,
        allowHunt = false,
        allowPlace = false,
        mcp = false,
        interruptImmediately = true;
    public boolean hud = true,
        hudLeft = false,
        hudShare = true,
        hudCompact = false,
        hudMetrics = false;
    public boolean hudTask = true, hudAlerts = true, publicAlerts = false, publicReceipts = false;
    public boolean helpNotifications = true, testIgnoreLowHealth = false;
    public boolean conversationMemory = true;
    public String apiProtocol = "CHAT";
    public boolean harnessFinishLocal = true,
        debugScene = false,
        selfDefense = true,
        proactiveDefense = false,
        attackNamed = false,
        attackPlayers = false;
    public int defenseSeconds = 20;
    public int avatarX = -1, avatarY = -1, avatarSize = 68;
    public int hudWidth = 190, hudY = 42, hudOpacity = 85, hudMessages = 3, hudVersion = 0;
    public int perceptionRadius = 12;
    public int maxOutput = 2400, idleSeconds = 90, maxTurns = 24, mcpPort = 25579;
  }

  public static class Data {
    public CommandProfile commands = new CommandProfile();
    public Map<String, JsonObject> commandBindings = new LinkedHashMap<>();
    public Set<String> revokedMembers = new LinkedHashSet<>();
    public List<TaskTimeline.Entry> taskEvents = new ArrayList<>();
    public BagPolicy bag = new BagPolicy();
    public List<StockTarget> stockTargets = new ArrayList<>();
    public Map<String, LogisticsRecord> logisticsRecords = new LinkedHashMap<>();
    public Map<String, JsonObject> favoriteOrders = new LinkedHashMap<>();
    public List<CommunityBoard.Member> members = new ArrayList<>();
    public CommunityBoard community = new CommunityBoard();
    public Map<String, UsageStats.Total> usageStats = new LinkedHashMap<>();
    public Map<String, JsonObject> inventoryMemories = new LinkedHashMap<>();
    public String resourceDelivery = "", productionSource = "";
    public List<WorkLedger.Entry> workLedger = new ArrayList<>();
    public Map<String, Place> places = new LinkedHashMap<>();
    public List<Fact> facts = new ArrayList<>();
    public Map<String, Skill> skills = new LinkedHashMap<>();
    public Map<String, SkillCheck> skillChecks = new LinkedHashMap<>();
    public Map<String, JsonObject> checkpoints = new LinkedHashMap<>();
    public Map<String, VisualNote> visualNotes = new LinkedHashMap<>();
    public List<String> journal = new ArrayList<>();
    public long inputTokens, outputTokens, requests;
    public WorldMemory world = new WorldMemory();
  }

  public static class Place {
    public String name, dimension, updated, block, inventory = "尚未核实";
    public int x, y, z;

    public BlockPos pos() {
      return new BlockPos(x, y, z);
    }
  }

  public static class Fact {
    public String text, source, at;

    public Fact(String t, String s) {
      text = t;
      source = s;
      at = Instant.now().toString();
    }
  }

  public static class Skill {
    public String description = "", status = "待验证", updated = "";
    public String originScope = "";
    public JsonArray steps = new JsonArray();
    public int successes, failures;
  }

  public static class SkillCheck {
    public String status = "当前世界待验证", definition = "";
    public int successes, failures;
    public long lastObserved;
    public String lastOutcome = "", lastDimension = "";
  }

  public static class VisualNote {
    public String text = "";
    public long observedAt;
  }

  private static String definition(String name) {
    var s = sharedSkills.get(name);
    return s == null ? "" : s.updated + ":" + s.steps.toString();
  }

  public static SkillCheck skillCheck(String name) {
    var check = data.skillChecks.get(name);
    return check != null && Objects.equals(check.definition, definition(name))
        ? check
        : new SkillCheck();
  }

  public static void recordSkill(String name, boolean success) {
    var check = skillCheck(name);
    check.definition = definition(name);
    data.skillChecks.put(name, check);
    if (success) check.successes++;
    else check.failures++;
    check.status = success ? "步骤执行完成，产物仍需核实" : "失败待修正";
    Learning.procedure(name, definition(name), success);
  }

  public static void recordCommandOutcome(String name, String result) {
    var check = skillCheck(name);
    check.definition = definition(name);
    check.lastObserved = System.currentTimeMillis();
    check.lastDimension = Minecraft.getInstance().level.dimension().location().toString();
    check.lastOutcome = ActionOutcome.describe(result).get("outcome").getAsString();
    if (check.lastOutcome.equals("verified")) check.successes++;
    else if (check.lastOutcome.equals("failed")) check.failures++;
    check.status = Rules.clean(TaskTimeline.clean(result), 250);
    data.skillChecks.put(name, check);
  }

  public static void recordPreset(String name, String result) {
    String key = "preset:" + name;
    var check = data.skillChecks.computeIfAbsent(key, k -> new SkillCheck());
    if (!Objects.equals(check.definition, SkillCatalog.VERSION)) {
      check = new SkillCheck();
      data.skillChecks.put(key, check);
    }
    check.definition = SkillCatalog.VERSION;
    if (result.startsWith("成功：")) check.successes++;
    else check.failures++;
    check.status = Rules.clean(result, 250);
    save();
  }

  public static JsonObject skillsView() {
    JsonObject out = new JsonObject();
    for (var e : sharedSkills.entrySet()) {
      var v = GSON.toJsonTree(e.getValue()).getAsJsonObject();
      v.remove("status");
      v.remove("successes");
      v.remove("failures");
      v.addProperty("scope", "shared_procedure");
      v.add("current_world_evidence", GSON.toJsonTree(skillCheck(e.getKey())));
      out.add(e.getKey(), v);
    }
    return out;
  }

  private static void mergeSkills(Data d, String source) {
    validateSkills(d.skills);
    if (d.skills != null)
      for (var skill : d.skills.values())
        if (skill != null && (skill.originScope == null || skill.originScope.isBlank()))
          skill.originScope = source;
    SharedSkills.merge(
        sharedSkills,
        d.skills,
        source.substring(0, Math.min(8, source.length())),
        (a, b) -> Objects.equals(a.description, b.description) && Objects.equals(a.steps, b.steps));
    d.skills = sharedSkills;
  }

  private static void validateSkills(Map<String, Skill> skills) {
    if (skills == null) return;
    for (var e : skills.entrySet())
      if (e.getKey() == null
          || e.getValue() == null
          || e.getValue().steps == null
          || e.getValue().steps.size() > 32) throw new IllegalArgumentException("无效的技能记录");
  }

  public static void importData(Data d) {
    normalize(d);
    mergeSkills(d, worldKey);
    data = d;
    worldReadable = true;
    save();
  }

  public static Path backupSkills() throws Exception {
    Files.createDirectories(ROOT);
    Path p = ROOT.resolve("skills-backup-" + System.currentTimeMillis() + ".json");
    Files.writeString(
        p,
        GSON.toJson(MemoryLayout.library(GSON.toJsonTree(sharedSkills))),
        StandardCharsets.UTF_8);
    return p;
  }

  public static void importSkills(String text) throws Exception {
    if (!libraryReadable) throw new IllegalStateException("原 skills.json 无法读取，请先保留并修复该文件");
    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
    if (!json.has("skills") || !json.get("skills").isJsonObject())
      throw new IllegalArgumentException("请复制技能库 JSON");
    Data d = GSON.fromJson(json, Data.class);
    if (d.skills == null) throw new IllegalArgumentException("技能库为空");
    for (var skill : d.skills.values())
      if (skill == null || skill.steps == null || skill.steps.size() > 32)
        throw new IllegalArgumentException("技能步骤格式无效");
    backupSkills();
    mergeSkills(d, "imported");
    save();
  }

  public static void saveSkill(String name, Skill skill) throws Exception {
    if (!libraryReadable) throw new IllegalStateException("全局技能库读取失败，本次禁止覆盖原文件");
    if (sharedSkills.containsKey(name)) backupSkills();
    sharedSkills.put(name, skill);
    save();
  }

  public static void init() {
    try {
      Files.createDirectories(ROOT);
      if (Files.exists(ROOT.resolve("settings.json")))
        config = GSON.fromJson(Files.readString(ROOT.resolve("settings.json")), Config.class);
    } catch (Exception e) {
      config = new Config();
    }
    if (config == null) config = new Config();
    config.visionMode = VisionPolicy.mode(config.visionMode, config.vision);
    Learning.init();
    if (config.hudVersion < 1) {
      config.publicReply = false;
      config.hudVersion = 1;
    }
    try {
      Path library = ROOT.resolve("skills.json");
      if (Files.exists(library)) {
        JsonObject json = JsonParser.parseString(Files.readString(library)).getAsJsonObject();
        if (!json.has("skills") || !json.get("skills").isJsonObject())
          throw new IllegalArgumentException("Invalid library");
        Data loaded = GSON.fromJson(json, Data.class);
        if (loaded == null || loaded.skills == null)
          throw new IllegalArgumentException("Invalid skill library");
        mergeSkills(loaded, "legacy");
      }
    } catch (Exception e) {
      libraryReadable = false;
      Companion.log("全局技能库读取失败，原 skills.json 将保留，本次不覆盖；请备份并检查文件。");
    }
    data.skills = sharedSkills;
    if (config.disabledPackages == null) config.disabledPackages = new java.util.ArrayList<>();
    SkillLibrary.refresh();
  }

  public static String scope() {
    return worldKey;
  }

  public static void connect() {
    Minecraft mc = Minecraft.getInstance();
    String server =
        mc.getCurrentServer() != null
            ? mc.getCurrentServer().ip
            : "singleplayer:"
                + (mc.getSingleplayerServer() != null
                    ? mc.getSingleplayerServer().getWorldData().getLevelName()
                    : "unknown");
    String next =
        mc.getSingleplayerServer() != null
            ? WorldScope.singleplayer(
                mc.getSingleplayerServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
            : WorldScope.key(server);
    if (next.equals(worldKey)) return;
    save();
    worldKey = next;
    data = new Data();
    worldReadable = true;
    try {
      if (Files.exists(dataFile())) {
        Data d = GSON.fromJson(Files.readString(dataFile()), Data.class);
        if (d == null) throw new IllegalArgumentException("Empty memory");
        validateSkills(d.skills);
        normalize(d);
        data = d;
      }
    } catch (Exception e) {
      worldReadable = false;
      Companion.log("记忆读取失败，原文件将保留且本次不会覆盖；已启用临时空白记忆。请先备份再导入有效记录。");
      try {
        Files.copy(dataFile(), ROOT.resolve("corrupt-" + System.currentTimeMillis() + ".json"));
      } catch (Exception ignored) {
      }
    }
    // Old singleplayer files used display names, which cannot safely identify a save folder.
    if (mc.getSingleplayerServer() != null
        && !Files.exists(dataFile())
        && Files.exists(ROOT.resolve("world-" + WorldScope.key(server) + ".json")))
      Companion.log("旧单人世界记忆按显示名保存，原文件已保留；新版本按存档路径隔离。请确认来源后通过面板导入旧备份。");
    migrateLegacySkills();
    mergeSkills(data, worldKey);
  }

  private static void migrateLegacySkills() {
    if (legacySkillsRead) return;
    legacySkillsRead = true;
    // Read only the small legacy skills section, never import another world's coordinates/facts.
    try (var files = Files.list(ROOT)) {
      for (Path path :
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith("world-")
                          && p.toString().endsWith(".json"))
              .sorted()
              .toList()) {
        if (Files.size(path) > 16_000_000) continue;
        try {
          JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
          if (!json.has("skills")) continue;
          Data old = new Data();
          JsonObject only = new JsonObject();
          only.add("skills", json.get("skills"));
          old = GSON.fromJson(only, Data.class);
          String scope = path.getFileName().toString().substring(6).replace(".json", "");
          mergeSkills(old, scope);
        } catch (Exception e) {
          Companion.log("未迁移技能文件：" + path.getFileName() + "；原文件保留。");
        }
      }
    } catch (Exception e) {
      Companion.log("旧技能迁移未完成：" + e.getClass().getSimpleName());
    }
  }

  private static Path dataFile() {
    return ROOT.resolve("world-" + worldKey + ".json");
  }

  public static void normalize(Data d) {
    if (d.revokedMembers == null) d.revokedMembers = new LinkedHashSet<>();
    if (d.taskEvents == null) d.taskEvents = new ArrayList<>();
    if (d.bag == null) d.bag = new BagPolicy();
    if (d.bag.desired == null) d.bag.desired = new LinkedHashMap<>();
    if (d.bag.mandatory == null) d.bag.mandatory = new LinkedHashSet<>();
    if (d.bag.protectedItems == null) d.bag.protectedItems = new LinkedHashSet<>();
    if (d.stockTargets == null) d.stockTargets = new ArrayList<>();
    d.stockTargets.removeIf(x -> x == null || x.plan == null);
    if (d.logisticsRecords == null) d.logisticsRecords = new LinkedHashMap<>();
    if (d.favoriteOrders == null) d.favoriteOrders = new LinkedHashMap<>();
    if (d.members == null) d.members = new ArrayList<>();
    d.members.removeIf(m -> m == null || m.name == null || m.uuid == null || m.role == null);
    for (var m : d.members) {
      if (m.id == null || m.id.isBlank()) m.id = UUID.randomUUID().toString();
      if (m.delivery == null) m.delivery = "";
      if (m.report == null) m.report = "DEFAULT";
    }
    if (d.community == null) d.community = new CommunityBoard();
    if (d.community.tasks == null) d.community.tasks = new ArrayList<>();
    d.community.tasks.removeIf(
        t ->
            t == null
                || t.id == null
                || t.sender == null
                || t.name == null
                || t.text == null
                || t.dimension == null
                || t.state == null);
    for (var t : d.community.tasks) {
      if (t.orders == null) t.orders = new ArrayList<>();
      if (t.requires == null) t.requires = new LinkedHashMap<>();
      if (t.note == null) t.note = "";
      if (t.delivery == null) t.delivery = "";
    }
    if (d.usageStats == null) d.usageStats = new LinkedHashMap<>();
    d.usageStats.entrySet().removeIf(e -> e.getValue() == null);
    d.resourceDelivery = Rules.clean(d.resourceDelivery, 128);
    d.productionSource = Rules.clean(d.productionSource, 128);
    if (d.world == null) d.world = new WorldMemory();
    d.world.normalize();
    if (d.inventoryMemories == null) d.inventoryMemories = new LinkedHashMap<>();
    InventoryMemory.normalize(d.inventoryMemories);
    if (d.visualNotes == null) d.visualNotes = new LinkedHashMap<>();
    if (d.skillChecks == null) d.skillChecks = new LinkedHashMap<>();
    if (d.places == null) d.places = new LinkedHashMap<>();
    if (d.facts == null) d.facts = new ArrayList<>();
    if (d.journal == null) d.journal = new ArrayList<>();
    if (d.workLedger == null) d.workLedger = new ArrayList<>();
    d.workLedger.removeIf(e -> e == null || e.text == null || e.dimension == null);
    if (d.workLedger.size() > 120) d.workLedger.subList(0, d.workLedger.size() - 120).clear();
  }

  public static void disconnect() {
    save();
    worldKey = "disconnected";
    data = new Data();
    data.skills = sharedSkills;
  }

  public static void save() {
    // Two launchers may share one game directory. The window selected in the
    // panel owns persistence until it exits; the passive window never overwrites it.
    if (controlLock == null || !controlLock.isValid()) {
      if (!Panel.managing(Minecraft.getInstance().screen) && !Agent.enabled) return;
      if (!claimControl()) return;
    }
    try {
      normalize(data);
      Files.createDirectories(ROOT);
      atomic(ROOT.resolve("settings.json"), GSON.toJson(config));
      Learning.save();
      atomic(ROOT.resolve("presets.json"), GSON.toJson(SkillCatalog.catalog()));
      if (libraryReadable)
        atomic(
            ROOT.resolve("skills.json"),
            GSON.toJson(MemoryLayout.library(GSON.toJsonTree(sharedSkills))));
      if (worldReadable && !worldKey.equals("disconnected"))
        atomic(
            dataFile(),
            GSON.toJson(MemoryLayout.world(GSON.toJsonTree(data).getAsJsonObject(), worldKey)));
    } catch (Exception e) {
      Companion.log("保存失败：" + e.getClass().getSimpleName());
    }
  }

  private static void atomic(Path p, String text) throws Exception {
    Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
    Files.writeString(tmp, text, StandardCharsets.UTF_8);
    try {
      Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static void fact(String text, String source) {
    data.facts.add(new Fact(Rules.clean(text, 2000), source));
    save();
  }

  public static void journal(String text) {
    data.journal.add(Instant.now() + " " + Rules.clean(text, 1000));
    if (data.journal.size() > 5000) data.journal.subList(0, 1000).clear();
  }

  public static Path backup() throws Exception {
    save();
    Path p = ROOT.resolve("backup-" + worldKey + "-" + System.currentTimeMillis() + ".json");
    Files.writeString(p, GSON.toJson(data));
    return p;
  }

  public static void restoreLatest() throws Exception {
    if (worldKey.equals("disconnected")) throw new IllegalStateException("请先进入世界");
    Path p;
    try (var stream = Files.list(ROOT)) {
      p =
          stream
              .filter(f -> f.getFileName().toString().startsWith("backup-" + worldKey + "-"))
              .max(Comparator.comparing(f -> f.getFileName().toString()))
              .orElseThrow(() -> new IllegalStateException("没有此服务器的备份"));
    }
    Data d = GSON.fromJson(Files.readString(p), Data.class);
    if (d == null || d.places == null || d.facts == null || d.skills == null)
      throw new IllegalStateException("备份格式不正确");
    normalize(d);
    backup();
    importData(d);
  }
}
