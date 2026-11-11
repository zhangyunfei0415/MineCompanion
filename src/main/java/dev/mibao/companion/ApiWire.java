package dev.mibao.companion;

import com.google.gson.*;
import java.net.URI;
import java.util.*;

/**
 * Normalizes the existing chat-completions protocol, without pretending other native APIs match it.
 */
public final class ApiWire {
  public static URI endpoint(String address) {
    URI base;
    try {
      base = URI.create(address.trim());
    } catch (Exception e) {
      throw new IllegalArgumentException("API地址格式不正确");
    }
    try {
      validate(base);
      String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
      if (path.endsWith("/responses") || path.endsWith("/messages"))
        throw new IllegalArgumentException("此处需要Chat Completions兼容接口；请选择对应的独立原生协议");
      if (!path.endsWith("/chat/completions")) path += "/chat/completions";
      return new URI(
          base.getScheme(), null, base.getHost(), base.getPort(), path, base.getQuery(), null);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("API地址格式不正确");
    }
  }

  public static void validate(URI uri) {
    String host = uri.getHost();
    if (host == null || uri.getUserInfo() != null || uri.getFragment() != null)
      throw new IllegalArgumentException("API地址必须包含主机，不能把密钥放进地址身份字段或片段");
    boolean local =
        Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(Locale.ROOT));
    if (!"https".equalsIgnoreCase(uri.getScheme())
        && !(local && "http".equalsIgnoreCase(uri.getScheme())))
      throw new IllegalArgumentException("远程API使用HTTPS；本机兼容接口可使用HTTP");
  }

  public static JsonObject message(JsonObject response) {
    if (response.has("error") && !response.get("error").isJsonNull())
      throw new IllegalArgumentException("提供商返回error字段，未执行工具；请核对接口协议和模型");
    if (!response.has("choices")
        || !response.get("choices").isJsonArray()
        || response.getAsJsonArray("choices").isEmpty())
      throw new IllegalArgumentException("接口未返回Chat Completions choices；请核对协议");
    JsonObject msg;
    try {
      msg =
          response
              .getAsJsonArray("choices")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("message")
              .deepCopy();
    } catch (Exception e) {
      throw new IllegalArgumentException("接口缺少assistant message");
    }
    if (msg.has("content") && !msg.get("content").isJsonNull()) {
      var content = msg.get("content");
      if (content.isJsonArray()) {
        StringBuilder text = new StringBuilder();
        for (var part : content.getAsJsonArray())
          if (part.isJsonObject()) {
            var p = part.getAsJsonObject();
            if (p.has("type")
                && p.get("type").isJsonPrimitive()
                && Set.of("text", "output_text").contains(p.get("type").getAsString())
                && p.has("text")
                && p.get("text").isJsonPrimitive()
                && p.getAsJsonPrimitive("text").isString())
              text.append(p.get("text").getAsString()).append('\n');
          }
        msg.addProperty("content", text.toString());
      } else if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("接口正文不是文本或文本块数组");
    }
    if (msg.has("tool_calls") && msg.get("tool_calls").isJsonNull()) msg.remove("tool_calls");
    boolean tools = msg.has("tool_calls");
    if (tools) {
      if (!msg.get("tool_calls").isJsonArray() || msg.getAsJsonArray("tool_calls").size() > 16)
        throw new IllegalArgumentException("单轮工具调用须为数组且不超过16项");
      var ids = new HashSet<String>();
      for (var call : msg.getAsJsonArray("tool_calls")) {
        var c = call.getAsJsonObject();
        String id = string(c, "id");
        if (id.isBlank() || id.length() > 256 || !ids.add(id))
          throw new IllegalArgumentException("工具调用id缺失或重复，整批未执行");
        if (!"function".equals(string(c, "type")))
          throw new IllegalArgumentException("只支持function工具调用");
        var f = c.getAsJsonObject("function");
        if (f == null || !Rules.TOOLS.contains(string(f, "name")))
          throw new IllegalArgumentException("接口返回未知游戏工具，整批未执行");
        JsonElement args = f.has("arguments") ? f.get("arguments") : new JsonObject();
        if (args.isJsonNull()) args = new JsonObject();
        if (args.isJsonPrimitive() && args.getAsJsonPrimitive().isString()) {
          String raw = args.getAsString().trim();
          try {
            args = raw.isEmpty() ? new JsonObject() : JsonParser.parseString(raw);
          } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是有效JSON，整批未执行");
          }
        }
        if (!args.isJsonObject() || args.toString().length() > 20000)
          throw new IllegalArgumentException("工具参数必须为有界JSON对象");
        f.addProperty("arguments", args.toString());
      }
      tools = !msg.getAsJsonArray("tool_calls").isEmpty();
    }
    if (!tools
        && (!msg.has("content")
            || msg.get("content").isJsonNull()
            || msg.get("content").getAsString().isBlank()))
      throw new IllegalArgumentException("接口没有返回可用文本或工具调用；推理内容不能作为执行结果");
    return msg;
  }

  private static String string(JsonObject o, String key) {
    var e = o.get(key);
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
        ? e.getAsString()
        : "";
  }

  public static String help() {
    return "协议选择：CHAT / RESPONSES / ANTHROPIC / GEMINI。\n"
               + "CHAT：DeepSeek及提供OpenAI兼容接口的服务，基础/v1/完整chat/completions地址。\n"
               + "RESPONSES：OpenAI或兼容网关，原生input/output/function_call_output；store=false并保留加密推理项。\n"
               + "ANTHROPIC：原生Messages，x-api-key、工具块与tool_result，保留thinking签名。\n"
               + "GEMINI：原生generateContent，x-goog-api-key、parts与functionResponse，保留thoughtSignature。\n"
               + "协议与地址分开选择；模型名需填写真实可用名称，不能只换URL。\n"
               + "均支持按需本地截图及一次文本降级；不跨厂商偷偷重试。\n"
               + "用量按协议/主机/模型在当前世界统计，未知字段不当作零，缓存/推理不重复加算。费用与余额未查询。\n"
               + "Harness独立于API，不要求模型密钥；本模组不知道外部规划器用量。\n"
               + "四协议支持原生SSE流式；中途断流不执行半截工具，可在显示设置关闭。已做模拟协议测试，真实厂商/网关仍需实测；不含Azure部署路由、Bedrock/Vertex云签名、音频或供应商内置工具。";
  }

  private ApiWire() {}
}
