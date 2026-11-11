package dev.mibao.companion;

import com.google.gson.*;
import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/** Optional authenticated stateless MCP endpoint, with full local harness tools. */
public final class McpBridge {
  private static HarnessHttp server;
  private static final Path DISCOVERY = Store.ROOT.resolve("harness-connection.json");

  public static void restart() {
    if (server != null) server.close();
    server = null;
    sync();
  }

  public static void sync() {
    if (!Store.config.mcp) {
      if (Agent.enabled && Store.config.driver.equals("HARNESS")) Agent.stop("本机Harness接口关闭");
      else HarnessController.interrupted("本机接口关闭");
      if (server != null) server.close();
      server = null;
      try {
        Files.deleteIfExists(DISCOVERY);
      } catch (Exception ignored) {
      }
      return;
    }
    if (server != null) return;
    try {
      server =
          new HarnessHttp(Store.config.mcpPort, () -> Store.config.mcpToken, McpBridge::dispatch);
      var connection = new JsonObject();
      connection.addProperty("url", "http://127.0.0.1:" + server.port() + "/mcp");
      connection.addProperty("token", Store.config.mcpToken);
      connection.addProperty("version", "0.7.6");
      Files.createDirectories(Store.ROOT);
      Files.writeString(DISCOVERY, connection.toString());
      Companion.log("本机MCP/Harness接口已启动：" + server.port() + "；认证信息只写入本机连接文件");
    } catch (Exception failed) {
      if (server != null) server.close();
      server = null;
      Companion.notifyUser("本机接口启动失败：" + failed.getClass().getSimpleName());
    }
  }

  private static CompletableFuture<JsonObject> dispatch(JsonObject req) {
    var result = new CompletableFuture<JsonObject>();
    Minecraft.getInstance()
        .execute(
            () -> {
              if (result.isCancelled()) return;
              try {
                String method = Agent.str(req, "method", "");
                switch (method) {
                  case "initialize" -> {
                    var o = new JsonObject();
                    o.addProperty("protocolVersion", "2025-03-26");
                    o.add("capabilities", JsonParser.parseString("{\"tools\":{}}"));
                    o.add(
                        "serverInfo",
                        JsonParser.parseString(
                            "{\"name\":\"logistics-companion\",\"version\":\"0.7.6\"}"));
                    result.complete(o);
                  }
                  case "ping" -> result.complete(new JsonObject());
                  case "tools/list" -> {
                    var o = new JsonObject();
                    o.add("tools", schema());
                    result.complete(o);
                  }
                  case "tools/call" -> {
                    var p = Agent.obj(req, "params");
                    var o = new JsonObject();
                    var content = new JsonArray();
                    var item = new JsonObject();
                    item.addProperty("type", "text");
                    try {
                      item.addProperty(
                          "text",
                          HarnessController.call(
                                  Agent.str(p, "name", ""), Agent.obj(p, "arguments"))
                              .toString());
                    } catch (Exception failure) {
                      o.addProperty("isError", true);
                      o.add("structuredContent", ReasonCodes.error(failure.getMessage()));
                      item.addProperty("text", Rules.clean(failure.getMessage(), 600));
                    }
                    content.add(item);
                    o.add("content", content);
                    result.complete(o);
                  }
                  default -> throw new IllegalArgumentException("Method not found");
                }
              } catch (Exception failed) {
                result.completeExceptionally(failed);
              }
            });
    return result;
  }

  public static JsonArray schema() {
    var a = new JsonArray();
    tool(
        a,
        "status",
        "Read structured client data, epoch/scope, active job, permission wait and runtime hook"
            + " diagnostics");
    tool(
        a,
        "capabilities",
        "Read build/protocol/tool count/lease/receipt/image behavior before integrating a new"
            + " controller");
    tool(
        a,
        "connect",
        "Claim one 60-second control lease; owner must select HARNESS and enable AI in game");
    tool(a, "heartbeat", "Renew lease; does not trigger actions", "lease:s!");
    tool(a, "stop", "Emergency stop; owner must re-enable AI in game afterward");
    tool(a, "release", "Release this controller and stop current work", "lease:s!");
    tool(a, "task", "Deliver a local task to the current API or HARNESS driver", "text:s!");
    tool(
        a,
        "events",
        "Read local user instructions and action events incrementally; truncated indicates a gap",
        "lease:s!",
        "after:i");
    tool(
        a,
        "reply",
        "Display a local-only assistant reply; never public chat",
        "lease:s!",
        "text:s!");
    tool(
        a,
        "record",
        "Start/stop bounded local JSONL observations and tool results",
        "lease:s!",
        "mode:s!");
    tool(
        a,
        "receipt",
        "Read original receipt in the current world even after stop; HTTP authentication remains"
            + " required; no action replay or lease renewal",
        "lease:s",
        "request_id:s!");
    tool(
        a,
        "screenshot",
        "Save world/container image locally; separate in-game image permission required",
        "lease:s!");
    var action =
        tool(
            a,
            "action",
            "Execute one existing game tool through normal permissions. Read status and tools"
                + " first. Same request_id+args returns original receipt. Return is not proof of"
                + " success.",
            "lease:s!",
            "request_id:s!",
            "epoch:i!",
            "scope:s!",
            "tool:s!");
    var args = new JsonObject();
    args.addProperty("type", "object");
    args.addProperty("additionalProperties", true);
    action.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("args", args);
    tool(a, "game_tools", "List all current game action schemas");
    return a;
  }

  private static JsonObject tool(JsonArray a, String name, String description, String... fields) {
    var t = new JsonObject();
    t.addProperty("name", name);
    t.addProperty("description", description);
    var schema = new JsonObject();
    schema.addProperty("type", "object");
    var props = new JsonObject();
    var required = new JsonArray();
    for (String field : fields) {
      String[] f = field.split(":");
      var p = new JsonObject();
      p.addProperty("type", f[1].startsWith("i") ? "integer" : "string");
      props.add(f[0], p);
      if (field.endsWith("!")) required.add(f[0]);
    }
    schema.add("properties", props);
    schema.add("required", required);
    t.add("inputSchema", schema);
    a.add(t);
    return t;
  }

  public static String config() {
    var host = new JsonObject();
    var entry = new JsonObject();
    var headers = new JsonObject();
    var servers = new JsonObject();
    entry.addProperty("url", "http://127.0.0.1:" + Store.config.mcpPort + "/mcp");
    headers.addProperty("Authorization", "Bearer " + Store.config.mcpToken);
    entry.add("headers", headers);
    servers.add("logistics-companion", entry);
    host.add("mcpServers", servers);
    return Store.GSON.toJson(host);
  }
}
