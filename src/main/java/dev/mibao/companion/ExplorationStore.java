package dev.mibao.companion;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/** Serial IO keeps a leave/re-enter load ordered behind the last write of that same shard. */
final class ExplorationStore {
  private static final System.Logger LOG = System.getLogger(ExplorationStore.class.getName());
  private static final Gson GSON = new Gson();
  private static final long MAX_BYTES = 32L * 1024 * 1024;
  private static final Map<Path, FileLock> LEASES = new HashMap<>();
  private static final ExecutorService IO = Executors.newSingleThreadExecutor(run -> {
    var thread = new Thread(run, "Companion-Exploration-IO");
    thread.setDaemon(true);
    return thread;
  });

  private record Shard(int schema, String scope, String dimension, ExplorationGraph.Snapshot graph) {}
  record Key(String scope, String dimension) {}
  record Loaded(ExplorationGraph graph, boolean writable, boolean failed) {
    Loaded(ExplorationGraph graph, boolean writable) {
      this(graph, writable, false);
    }
  }

  static Path path(Path root, Key key) {
    try {
      byte[] bytes = (key.scope + "\n" + key.dimension).getBytes(StandardCharsets.UTF_8);
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      var dimension = key.dimension.replaceAll("[^a-zA-Z0-9_-]", "_");
      if (dimension.length() > 48) dimension = dimension.substring(0, 48);
      return root.resolve("exploration").resolve("world-" + hash + "-" + dimension + ".graph.json");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static CompletableFuture<Loaded> load(Path root, Key key) {
    return load(root, key, false);
  }

  /** Lock poll: reads the shard only after this client actually obtained the write lock, else null. */
  static CompletableFuture<Loaded> claimAndLoad(Path root, Key key) {
    return load(root, key, true);
  }

  private static CompletableFuture<Loaded> load(Path root, Key key, boolean onlyIfClaimed) {
    return CompletableFuture.supplyAsync(() -> {
      Path path = path(root, key);
      boolean writable = false, reading = false;
      try {
        writable = claim(path);
        if (onlyIfClaimed && !writable) return null;
        reading = true;
        if (Files.notExists(path)) return new Loaded(new ExplorationGraph(), writable);
        if (Files.size(path) > MAX_BYTES) throw new IOException("Exploration shard too large");
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          Shard shard = GSON.fromJson(reader, Shard.class);
          if (shard == null || shard.schema != 1 || !key.scope.equals(shard.scope)
              || !key.dimension.equals(shard.dimension)) throw new IOException("Wrong shard header");
          return new Loaded(ExplorationGraph.from(shard.graph), writable);
        }
      } catch (IOException | RuntimeException e) {
        if (!reading && onlyIfClaimed) return null;
        LOG.log(System.Logger.Level.WARNING, "Exploration shard could not be loaded: " + path, e);
        if (!reading) return new Loaded(new ExplorationGraph(), false);
        // Do not keep a lease for a shard this client cannot use; later polls must be able to claim again.
        if (writable) unlock(path);
        return new Loaded(new ExplorationGraph(), false, true);
      }
    }, IO);
  }

  private static void unlock(Path path) {
    var lock = LEASES.remove(path);
    if (lock == null) return;
    try {
      lock.channel().close();
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Could not close exploration shard lock", e);
    }
  }

  private static boolean claim(Path path) throws IOException {
    if (LEASES.containsKey(path)) return false;
    Files.createDirectories(path.getParent());
    var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock = null;
    try {
      lock = channel.tryLock();
      if (lock == null) return false;
      LEASES.put(path, lock);
      return true;
    } finally {
      if (lock == null) channel.close();
    }
  }

  static CompletableFuture<Void> release(Path root, Key key) {
    return CompletableFuture.runAsync(() -> {
      var lock = LEASES.remove(path(root, key));
      if (lock == null) return;
      try {
        lock.channel().close();
      } catch (IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Could not close exploration shard lock", e);
      }
    }, IO);
  }

  static CompletableFuture<Void> save(Path root, Key key, ExplorationGraph.Snapshot snapshot) {
    return CompletableFuture.runAsync(() -> {
      Path path = path(root, key);
      Path temp = null;
      try {
        if (!LEASES.containsKey(path)) throw new IOException("Exploration shard has no write lock");
        Files.createDirectories(path.getParent());
        temp = Files.createTempFile(path.getParent(), "graph-", ".tmp");
        try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
          GSON.toJson(new Shard(1, key.scope, key.dimension, snapshot), writer);
        }
        try {
          Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException | RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "Exploration shard could not be saved: " + path, e);
        throw new CompletionException(e);
      } finally {
        if (temp != null) {
          try {
            Files.deleteIfExists(temp);
          } catch (IOException e) {
          LOG.log(System.Logger.Level.DEBUG, "Could not remove exploration staging file " + temp, e);
          }
        }
      }
    }, IO);
  }

  static CompletableFuture<Loaded> reloadReadOnly(Path root, Key key) {
    return CompletableFuture.supplyAsync(() -> {
      Path path = path(root, key);
      try {
        if (Files.notExists(path)) return new Loaded(new ExplorationGraph(), false);
        if (Files.size(path) > MAX_BYTES) throw new IOException("Exploration shard too large");
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          Shard shard = GSON.fromJson(reader, Shard.class);
          if (shard == null || shard.schema != 1 || !key.scope.equals(shard.scope)
              || !key.dimension.equals(shard.dimension)) throw new IOException("Wrong shard header");
          return new Loaded(ExplorationGraph.from(shard.graph), false);
        }
      } catch (IOException | RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "Exploration shard could not be read: " + path, e);
        return new Loaded(new ExplorationGraph(), false, true);
      }
    }, IO);
  }

  private ExplorationStore() {}
}
