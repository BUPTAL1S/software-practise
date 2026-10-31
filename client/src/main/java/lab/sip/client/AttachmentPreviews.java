package lab.sip.client;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import javafx.scene.image.Image;

/** Small authenticated thumbnail cache. Never performs HTTP or decoding on the FX thread. */
final class AttachmentPreviews implements AutoCloseable {
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(32), task -> { Thread t = new Thread(task, "attachment-preview"); t.setDaemon(true); return t; });
  private final LinkedHashMap<String, CompletableFuture<Image>> cache = new LinkedHashMap<>(64, .75f, true);
  synchronized CompletableFuture<Image> load(HttpFileClient client, String url) {
    String key = client.getEffectiveBaseUrl() + "|" + client.getAuthToken() + "|" + url;
    var existing = cache.get(key);
    if (existing != null) return existing;
    var future = new CompletableFuture<Image>();
    cache.put(key, future);
    while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
    try {
      workers.execute(() -> {
        try {
          byte[] bytes = client.downloadThumbnail(url);
          // Reject pathological dimensions before JavaFX allocates the decoded bitmap.
          try (var input = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new java.io.IOException("不支持的图片格式");
            var reader = readers.next();
            try {
              reader.setInput(input);
              int w = reader.getWidth(0), h = reader.getHeight(0);
              if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || (long) w * h > 16_777_216)
                throw new java.io.IOException("图片尺寸过大");
            } finally { reader.dispose(); }
          }
          Image image = new Image(new ByteArrayInputStream(bytes), 180, 180, true, true);
          if (image.isError()) throw new java.io.IOException("图片解码失败", image.getException());
          future.complete(image);
        } catch (Exception failure) { future.completeExceptionally(failure); }
      });
    } catch (RejectedExecutionException busy) { cache.remove(key); future.completeExceptionally(busy); }
    return future;
  }
  @Override public synchronized void close() { workers.shutdownNow(); cache.clear(); }
}
