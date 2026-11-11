package dev.mibao.companion;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Read-only status sharing between this user's game windows. No commands or credentials. */
public final class StatusLink {
  private static final ExecutorService IO =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Companion-HUD-Link");
            t.setDaemon(true);
            return t;
          });
  private static final Path ROOT =
      Path.of(
          System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
          "LogisticsCompanion",
          "hud");
  private static final String INSTANCE = UUID.randomUUID().toString();
  private static volatile StatusFeed.View remote;
  private static volatile String remoteScope = "", error = "";
  private static volatile boolean busy;
  private static long next;
  private static boolean published;

  public static String error() {
    return error;
  }

  public static StatusFeed.View remote() {
    var v = remote;
    return Store.config.hudShare && remoteScope.equals(Store.scope()) ? v : null;
  }

  public static void tick() {
    long now = System.currentTimeMillis();
    if (busy || now < next || Store.scope().equals("disconnected")) return;
    next = now + 1000;
    String scope = Store.scope();
    Path file = ROOT.resolve(scope + ".json");
    // Continue a heartbeat after a stop so the owner can see the help/stop message.
    boolean write = Store.config.hudShare && (Agent.enabled || published && Store.hasControl());
    if (!Store.config.hudShare) {
      remote = null;
      return;
    }
    String json = write ? Store.GSON.toJson(Hud.localView()) : null;
    if (write) published = true;
    busy = true;
    IO.submit(
        () -> {
          try {
            if (write) {
              Files.createDirectories(ROOT);
              Path tmp = ROOT.resolve(scope + "-" + INSTANCE + ".tmp");
              Files.writeString(tmp, json, StandardCharsets.UTF_8);
              try {
                Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
              } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
              }
            } else if (Files.isRegularFile(file) && Files.size(file) < 256000) {
              var v = Store.GSON.fromJson(Files.readString(file), StatusFeed.View.class);
              if (v != null
                  && v.kind != null
                  && v.name != null
                  && v.detail != null
                  && v.task != null
                  && scope.equals(v.scope)
                  && System.currentTimeMillis() - v.updated < 7000) {
                remote = v;
                remoteScope = scope;
              }
            }
            error = "";
          } catch (Exception e) {
            error = "状态同步：" + e.getClass().getSimpleName();
          } finally {
            busy = false;
          }
        });
  }

  public static void reset() {
    remote = null;
    remoteScope = "";
    published = false;
    next = 0;
  }
}
