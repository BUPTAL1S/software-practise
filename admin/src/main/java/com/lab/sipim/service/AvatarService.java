package com.lab.sipim.service;

import com.lab.sipim.domain.FileIndex;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.FileIndexRepository;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.IdentityRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AvatarService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    private static final int AVATAR_SIZE = 400;
    public static final long MAX_AVATAR_SIZE_BYTES = 10 * 1024 * 1024L; // 10MB limit

    private final UserRepository userRepo;
    private final FileIndexRepository fileRepo;
    @Value("${sipim.avatar.upload-dir:uploads}")
    private String configuredUploadDir;
    @Value("${sipim.avatar.fs-dir:}")
    private String configuredFsAvatarDir;
    private Path uploadDir;
    private Path fsAvatarDir;

    public AvatarService(UserRepository userRepo, FileIndexRepository fileRepo) {
        this.userRepo = userRepo;
        this.fileRepo = fileRepo;
    }

    @PostConstruct
    public void init() {
        System.setProperty("java.awt.headless", "true");
        uploadDir = Paths.get(configuredUploadDir);
        fsAvatarDir = configuredFsAvatarDir == null || configuredFsAvatarDir.isBlank()
                ? resolveFsAvatarDir() : Paths.get(configuredFsAvatarDir);
        try {
            Files.createDirectories(fsAvatarDir);
            Files.createDirectories(uploadDir);
            generateDefaultAvatarIfMissing();
        } catch (Exception e) {
            log.warn("Failed to initialize avatar directories: {}", e.getMessage());
        }
    }

    private Path resolveFsAvatarDir() {
        Path p = Paths.get("server/freeswitch/avatars");
        if (Files.exists(p.getParent())) {
            return p;
        }
        Path parentRel = Paths.get("../server/freeswitch/avatars");
        if (Files.exists(parentRel.getParent())) {
            return parentRel;
        }
        return p;
    }

    public Path getFsAvatarDir() {
        if (fsAvatarDir == null) {
            fsAvatarDir = resolveFsAvatarDir();
        }
        return fsAvatarDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<User> users = userRepo.findAll();
            for (User u : users) {
                try { ensureUserAvatar(u); }
                catch (IllegalArgumentException invalid) {
                    log.warn("Skipped unsafe legacy avatar identity for user id {}", u.getId());
                }
            }
            log.info("Initialized avatars for {} users in {}", users.size(), getFsAvatarDir());
        } catch (Exception e) {
            log.warn("Error ensuring initial user avatars: {}", e.getMessage());
        }
    }

    public void ensureUserAvatar(User user) {
        if (user == null) return;
        String sipId = user.getSipId();
        String username = user.getUsername();
        if (sipId == null || sipId.isBlank()) return;

        validateIdentity(user);
        Path targetPng = IdentityRules.avatarPath(getFsAvatarDir(), sipId);
        if (Files.exists(targetPng) && targetPng.toFile().length() > 0) {
            // Also ensure username.png exists for fallback
            if (username != null && !username.isBlank()) {
                Path uPng = IdentityRules.avatarPath(getFsAvatarDir(), username);
                if (!Files.exists(uPng)) {
                    try {
                        Files.copy(targetPng, uPng, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {}
                }
            }
            return;
        }

        // If user already has custom avatarUrl, try to load it
        boolean loaded = false;
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            loaded = tryCopyAvatarFromUrl(user.getAvatarUrl(), sipId, username);
        }

        // If no custom avatar, generate a handsome placeholder avatar
        if (!loaded) {
            generatePlaceholderAvatar(sipId, username);
        }
    }

    private boolean tryCopyAvatarFromUrl(String avatarUrl, String sipId, String username) {
        try {
            // avatarUrl format: /api/files/{id}
            if (avatarUrl.contains("/files/")) {
                String idStr = avatarUrl.substring(avatarUrl.lastIndexOf('/') + 1);
                long fileId = Long.parseLong(idStr);
                Optional<FileIndex> found = fileRepo.findById(fileId);
                if (found.isPresent()) {
                    Path source = Paths.get(found.get().getPath());
                    if (Files.exists(source)) {
                        BufferedImage img;
                        try (InputStream input = Files.newInputStream(source)) { img = readBoundedImage(input); }
                        if (img != null) {
                            BufferedImage processed = createCircularAvatar(img);
                            saveAvatarImage(processed, sipId, username);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to copy avatar from url {}: {}", avatarUrl, e.getMessage());
        }
        return false;
    }

    public String saveUploadedAvatar(User user, MultipartFile file) throws IOException {
        validateIdentity(user);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Avatar file cannot be empty");
        }

        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new IllegalArgumentException("Avatar file size exceeds 10MB limit (size: " + file.getSize() + " bytes)");
        }

        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.toLowerCase();
            if (!ct.startsWith("image/") && !ct.equals("application/octet-stream")) {
                throw new IllegalArgumentException("Unsupported file type: " + contentType + ". Only images (JPG, PNG, WEBP, GIF) are allowed.");
            }
        }
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (!ext.matches("png|jpg|jpeg|webp|gif|bmp")) {
                throw new IllegalArgumentException("Unsupported file extension: ." + ext + ". Only JPG, PNG, WEBP, GIF are allowed.");
            }
        }

        BufferedImage original;
        try (InputStream input = file.getInputStream()) { original = readBoundedImage(input); }
        if (original == null || original.getWidth() <= 0 || original.getHeight() <= 0) {
            throw new IllegalArgumentException("Unsupported or corrupted image format. Please upload JPG, PNG, or WEBP.");
        }
        if (original.getWidth() > 8192 || original.getHeight() > 8192) {
            throw new IllegalArgumentException("Image dimensions too large (" + original.getWidth() + "x" + original.getHeight() + "). Max supported is 8192x8192.");
        }

        BufferedImage avatarImg = createCircularAvatar(original);

        // 1. Save to uploads/ directory and index as FileIndex
        Files.createDirectories(uploadDir);
        String fileName = "avatar_" + user.getSipId() + "_" + UUID.randomUUID() + ".png";
        Path uploadTarget = uploadDir.resolve(fileName);
        ImageIO.write(avatarImg, "png", uploadTarget.toFile());

        FileIndex index = new FileIndex();
        index.setFromUser(user.getUsername());
        index.setToUserOrRoom("profile");
        index.setPath(uploadTarget.toString());
        index.setSize(Files.size(uploadTarget));
        index.setCreatedAt(Instant.now());
        FileIndex savedIndex = fileRepo.save(index);

        String avatarUrl = "/api/files/" + savedIndex.getId();
        user.setAvatarUrl(avatarUrl);
        userRepo.save(user);

        // 2. Save directly to FreeSWITCH avatars folder
        saveAvatarImage(avatarImg, user.getSipId(), user.getUsername());

        log.info("Saved custom avatar for user {} ({}) -> {}", user.getUsername(), user.getSipId(), uploadTarget);
        return avatarUrl;
    }

    public void resetToDefaultAvatar(User user) {
        validateIdentity(user);
        user.setAvatarUrl(null);
        userRepo.save(user);
        generatePlaceholderAvatar(user.getSipId(), user.getUsername());
    }

    private void saveAvatarImage(BufferedImage img, String sipId, String username) {
        try {
            Path targetSip = IdentityRules.avatarPath(getFsAvatarDir(), sipId);
            ImageIO.write(img, "png", targetSip.toFile());

            if (username != null && !username.isBlank()) {
                Path targetUser = IdentityRules.avatarPath(getFsAvatarDir(), username);
                ImageIO.write(img, "png", targetUser.toFile());
            }
        } catch (IOException e) {
            log.error("Failed to save avatar image for {}: {}", sipId, e.getMessage());
        }
    }

    public void generatePlaceholderAvatar(String sipId, String username) {
        IdentityRules.avatarPath(getFsAvatarDir(), sipId);
        if (username != null && !username.isBlank()) IdentityRules.avatarPath(getFsAvatarDir(), username);
        BufferedImage img = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Dark slate backdrop matching WeChat / modern meeting room UI
        g2.setColor(new Color(15, 23, 42)); // #0F172A
        g2.fillRect(0, 0, AVATAR_SIZE, AVATAR_SIZE);

        // Pick distinct vibrant color for user circle based on hash
        int hash = (sipId != null ? sipId : (username != null ? username : "guest")).hashCode();
        Color[] accentColors = {
                new Color(37, 99, 235),  // Royal Blue #2563EB
                new Color(124, 58, 237), // Purple #7C3AED
                new Color(5, 150, 105),  // Emerald #059669
                new Color(217, 119, 6),  // Amber #D97706
                new Color(225, 29, 72),  // Rose #E11D48
                new Color(13, 148, 136)  // Teal #0D9488
        };
        Color circleColor = accentColors[Math.floorMod(hash, accentColors.length)];

        // Draw central circle
        // Draw central circle (perfectly centered)
        int circlePadding = 50;
        int circleDiameter = AVATAR_SIZE - (circlePadding * 2); // 300px
        int circleX = (AVATAR_SIZE - circleDiameter) / 2;
        int circleY = (AVATAR_SIZE - circleDiameter) / 2;

        g2.setColor(circleColor);
        g2.fill(new Ellipse2D.Double(circleX, circleY, circleDiameter, circleDiameter));

        // Subtle outer glow/border
        g2.setColor(new Color(255, 255, 255, 60));
        g2.setStroke(new BasicStroke(3f));
        g2.draw(new Ellipse2D.Double(circleX, circleY, circleDiameter, circleDiameter));

        // Draw center text (Initial or SIP ID)
        String centerText = (sipId != null && !sipId.isBlank()) ? sipId : (username != null ? username : "?");
        if (centerText.length() > 4) {
            centerText = centerText.substring(0, 4);
        }
        g2.setColor(Color.WHITE);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 76);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int textX = (AVATAR_SIZE - fm.stringWidth(centerText)) / 2;
        int textY = circleY + ((circleDiameter - fm.getHeight()) / 2) + fm.getAscent();
        g2.drawString(centerText, textX, textY);

        g2.dispose();
        saveAvatarImage(img, sipId, username);
    }

    private void generateDefaultAvatarIfMissing() {
        Path defaultPng = getFsAvatarDir().resolve("default.png");
        if (!Files.exists(defaultPng) || defaultPng.toFile().length() == 0) {
            generatePlaceholderAvatar("default", null);
        }
    }

    private void validateIdentity(User user) {
        if (user == null) throw new IllegalArgumentException("用户不能为空");
        IdentityRules.avatarPath(getFsAvatarDir(), user.getSipId());
        IdentityRules.avatarPath(getFsAvatarDir(), user.getUsername());
    }

    /** Inspect dimensions before allocating decoded pixels (compressed size alone is insufficient). */
    static BufferedImage readBoundedImage(InputStream input) throws IOException {
        try (var imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw new IllegalArgumentException("无法读取头像图片");
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IllegalArgumentException("不支持或已损坏的图片格式");
            var reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                        || (long) width * height > 16_777_216L) {
                    throw new IllegalArgumentException("头像尺寸过大，最大边长 8192、总像素不超过 1600 万");
                }
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private BufferedImage createCircularAvatar(BufferedImage src) {
        BufferedImage out = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // Entire circle fills the 400x400 canvas edge-to-edge without any dark/black border padding
        int diameter = AVATAR_SIZE;
        int circleX = 0;
        int circleY = 0;

        // Clip circular region
        Shape circle = new Ellipse2D.Double(circleX, circleY, diameter, diameter);
        g2.setClip(circle);

        // Draw aspect-ratio preserved image (center-fit crop without squishing)
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        double scale = Math.max((double) diameter / srcW, (double) diameter / srcH);
        int drawW = (int) Math.round(srcW * scale);
        int drawH = (int) Math.round(srcH * scale);
        int drawX = (diameter - drawW) / 2;
        int drawY = (diameter - drawH) / 2;

        g2.drawImage(src, drawX, drawY, drawW, drawH, null);
        g2.setClip(null);

        g2.dispose();
        return out;
    }
}
