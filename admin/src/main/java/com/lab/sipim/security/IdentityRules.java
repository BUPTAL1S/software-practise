package com.lab.sipim.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** Account keys also occur in SIP URIs and MCU avatar filenames. Nicknames are separate. */
public final class IdentityRules {
    private IdentityRules() {}

    public static boolean valid(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) return false;
        String key = value.toLowerCase(Locale.ROOT);
        return !key.startsWith("room-") && !key.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]");
    }

    public static boolean passwordFits(String password) {
        return password != null && !password.isBlank()
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public static Path avatarPath(Path directory, String identity) {
        if (!valid(identity)) throw new IllegalArgumentException("账号或 SIP 分机号格式无效");
        Path root = directory.toAbsolutePath().normalize();
        Path target = root.resolve(identity + ".png").normalize();
        if (!target.startsWith(root) || !root.equals(target.getParent())) {
            throw new IllegalArgumentException("头像路径超出存储目录");
        }
        return target;
    }
}
