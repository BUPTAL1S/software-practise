package lab.sip.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import javafx.scene.image.Image;

/** Small authenticated thumbnail cache. Never performs HTTP or decoding on the FX thread. */
final class AttachmentPreviews implements AutoCloseable {
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(32), task -> { Thread t = new Thread(task, "attachment-preview"); t.setDaemon(true); return t; });
  private final LinkedHashMap<String, CompletableFuture<Image>> cache = new LinkedHashMap<>(64, .75f, true);
  CompletableFuture<Image> load(HttpFileClient client, String url) {
    String key = client.getEffectiveBaseUrl() + "|" + client.getAuthToken() + "|" + url;
    return load(key, () -> AttachmentMedia.loadImage(client.downloadThumbnail(url), 180, 180));
  }
  CompletableFuture<Image> loadLocal(Path path) {
    try {
      String key = "local|" + path.toAbsolutePath() + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path);
      return load(key, () -> AttachmentMedia.loadImage(path, 180, 180));
    } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
  }
  private synchronized CompletableFuture<Image> load(String key, Callable<Image> loader) {
    var existing = cache.get(key);
    if (existing != null) return existing;
    var future = new CompletableFuture<Image>();
    cache.put(key, future);
    while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
    try {
      workers.execute(() -> {
        try {
          future.complete(loader.call());
        } catch (Exception failure) { future.completeExceptionally(failure); }
      });
    } catch (RejectedExecutionException busy) { cache.remove(key); future.completeExceptionally(busy); }
    return future;
  }
  @Override public synchronized void close() {
    workers.shutdownNow();
    cache.values().forEach(future -> future.cancel(false));
    cache.clear();
  }
}
