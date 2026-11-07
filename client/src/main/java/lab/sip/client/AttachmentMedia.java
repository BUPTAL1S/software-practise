package lab.sip.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** File classification and bounded image decoding, shared by chat and the preview window. */
final class AttachmentMedia {
  enum Kind { IMAGE, VIDEO, OTHER }

  static final long MAX_BYTES = 64L * 1024 * 1024;
  private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
  private static final Set<String> VIDEOS = Set.of("mp4", "m4v", "mov", "mkv", "avi", "webm");

  static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  static Kind kind(String name) {
    String ext = extension(name);
    return IMAGES.contains(ext) ? Kind.IMAGE : VIDEOS.contains(ext) ? Kind.VIDEO : Kind.OTHER;
  }

  static boolean supportsVideo(String name) {
    String ext = extension(name);
    return ext.equals("mp4") || ext.equals("m4v");
  }

  /** Call on a worker: inspect dimensions before allocating a decoded bitmap. */
  static Image loadImage(Path path, int width, int height) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("图片超过 64 MiB，请下载后查看");
    try (var input = new FileImageInputStream(path.toFile())) {
      return decode(input, width, height);
    }
  }

  static Image loadImage(byte[] bytes, int width, int height) throws IOException {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      return decode(input, width, height);
    }
  }

  private static Image decode(ImageInputStream input, int maxWidth, int maxHeight) throws IOException {
    var readers = ImageIO.getImageReaders(input);
    if (!readers.hasNext()) throw new IOException("暂不支持这种图片格式，请下载后查看");
    var reader = readers.next();
    try {
      reader.setInput(input, true, true);
      int w = reader.getWidth(0), h = reader.getHeight(0);
      if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long) w * h > 40_000_000)
        throw new IOException("图片尺寸过大，请下载后查看");
      int sample = Math.max((w + maxWidth - 1) / maxWidth, (h + maxHeight - 1) / maxHeight);
      var parameters = reader.getDefaultReadParam();
      parameters.setSourceSubsampling(sample, sample, 0, 0);
      // Render one frame so animated GIFs cannot allocate a bitmap per frame.
      var frame = reader.read(0, parameters);
      int width = frame.getWidth(), height = frame.getHeight();
      var image = new WritableImage(width, height);
      image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(),
          frame.getRGB(0, 0, width, height, null, 0, width), 0, width);
      return image;
    } finally { reader.dispose(); }
  }

  private AttachmentMedia() { }
}
