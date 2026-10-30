package com.lab.sipim.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** One policy for the workbench and legacy API aliases; public client directory stays public. */
@Configuration
public class ApiAccessPolicy implements HandlerInterceptor, WebMvcConfigurer {
    private final LoginTokens tokens;
    private final UserRepository users;
    private final ObjectMapper json;

    public ApiAccessPolicy(LoginTokens tokens, UserRepository users, ObjectMapper json) {
        this.tokens = tokens; this.users = users; this.json = json;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    public static boolean isAdmin(User user) {
        return "1001".equals(user.getUsername()) && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private static String recipientKey(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.regionMatches(true, 0, "sip:", 0, 4)) result = result.substring(4);
        int at = result.indexOf('@');
        return at < 0 ? result : result.substring(0, at);
    }

    public static boolean isIdentity(User user, String value) {
        if (value == null) return false;
        String raw = value.trim();
        String key = recipientKey(value);
        // A full SIP address identifies an extension, not an arbitrary username
        // that happens to contain the same URI text.
        if (raw.regionMatches(true, 0, "sip:", 0, 4) || raw.contains("@")) return user.getSipId().equals(key);
        return user.getUsername().equals(raw) || user.getSipId().equals(raw);
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
        String method = request.getMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        boolean adminOnly = path.startsWith("/api/admin/") && !path.equals("/api/admin/login")
                || path.equals("/api/users/online")
                || path.equals("/api/files") && read
                || path.matches("/api/cdr/[0-9]+") && read
                || path.equals("/api/users") && method.equals("POST");
        boolean publicRoute = method.equals("POST") && (path.equals("/api/login")
                || path.equals("/api/register") || path.equals("/api/admin/login"))
                || read && (path.equals("/api/health") || path.equals("/api/users")
                || path.matches("/api/users/[^/]+") || path.equals("/api/rooms")
                || path.matches("/api/rooms/[^/]+")
                // JavaFX avatars and chat-image previews use this existing client URL without headers.
                // Management file lists/downloads use the separate protected workbench routes.
                || path.matches("/api/files/[0-9]+"));
        if (publicRoute && !adminOnly) return true;

        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
        User user = tokens.userIdOf(token).flatMap(users::findById).orElse(null);
        if (user == null) return deny(response, 401, "unauthorized");
        if (adminOnly && !isAdmin(user)) return deny(response, 403, "仅管理员账号 1001 可以访问管理工作台");
        if (isAdmin(user)) return true;

        if (path.equals("/api/cdr") && read) {
            String caller = request.getParameter("caller"), callee = request.getParameter("callee");
            boolean own = isIdentity(user, caller)
                    || (caller == null || caller.isBlank()) && isIdentity(user, callee);
            if (!own) return deny(response, 403, "只能查询自己的通话记录");
        }
        if (path.matches("/api/rooms/[^/]+/vmute")) {
            String target = request.getParameter("sipId");
            if (!isIdentity(user, target))
                return deny(response, 403, "只能切换自己的会议视频状态");
        }
        return true;
    }

    private boolean deny(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("error", message));
        return false;
    }
}
