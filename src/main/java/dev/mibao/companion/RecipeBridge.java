package dev.mibao.companion;

import com.google.gson.*;
import java.util.*;

/**
 * Optional JEI boundary: core classes never reference JEI classes. All calls run on the client
 * thread.
 */
public final class RecipeBridge {
  public interface Provider {
    JsonObject status();

    JsonObject items(String query, int offset, int limit);

    JsonObject lookup(String item, String role, String category, int offset, int limit);

    JsonObject details(String token);

    String show(String token);

    default JsonObject transfer(String token, boolean execute, boolean max) {
      throw new IllegalArgumentException("[TRANSFER_UNSUPPORTED] 当前JEI桥不支持配方转移");
    }

    default void invalidate() {}
  }

  private static Provider provider;

  public static void available(Provider p) {
    provider =
        new Provider() {
          private <T> T call(java.util.function.Supplier<T> f) {
            try {
              return f.get();
            } catch (LinkageError e) {
              throw new IllegalStateException(
                  "JEI接口版本不兼容：" + e.getClass().getSimpleName() + "；请保留日志并使用客户端配方查询", e);
            }
          }

          public JsonObject status() {
            return call(p::status);
          }

          public JsonObject items(String q, int o, int l) {
            return call(() -> p.items(q, o, l));
          }

          public JsonObject lookup(String i, String r, String c, int o, int l) {
            return call(() -> p.lookup(i, r, c, o, l));
          }

          public JsonObject details(String t) {
            return call(() -> p.details(t));
          }

          public String show(String t) {
            return call(() -> p.show(t));
          }

          public JsonObject transfer(String t, boolean execute, boolean max) {
            return call(() -> p.transfer(t, execute, max));
          }

          public void invalidate() {
            call(
                () -> {
                  p.invalidate();
                  return null;
                });
          }
        };
  }

  public static void unavailable() {
    provider = null;
  }

  public static void changed() {
    if (provider != null)
      try {
        provider.invalidate();
      } catch (RuntimeException e) {
        unavailable();
      }
  }

  public static JsonObject status() {
    if (provider != null)
      try {
        return provider.status();
      } catch (RuntimeException e) {
        var error = new JsonObject();
        error.addProperty("ready", false);
        error.addProperty("reason", e.getMessage());
        return error;
      }
    var o = new JsonObject();
    o.addProperty("ready", false);
    o.addProperty("reason", "JEI未安装、版本不兼容或运行时尚未就绪；仍可查询客户端同步配方");
    return o;
  }

  public static Provider require() {
    if (provider == null)
      throw new IllegalArgumentException(
          "JEI运行时不可用；recipe_status查看状态，lookup_client_recipes可查询同步配方");
    return provider;
  }

  public static int limit(int n) {
    return Rules.bounded(n, 1, 20);
  }

  public static int offset(int n) {
    if (n < 0 || n > 10000) throw new IllegalArgumentException("offset须0..10000");
    return n;
  }

  private RecipeBridge() {}
}
