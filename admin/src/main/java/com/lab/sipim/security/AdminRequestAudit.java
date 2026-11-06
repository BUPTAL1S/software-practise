package com.lab.sipim.security;
import com.lab.sipim.service.AdminAudit;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class AdminRequestAudit implements HandlerInterceptor,WebMvcConfigurer {
    private final AdminAudit audit;
    public AdminRequestAudit(AdminAudit audit){this.audit=audit;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/admin/**");}
    @Override public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception error) {
        if(!Boolean.TRUE.equals(req.getAttribute("auditRecorded")) && (res.getStatus()>=400 || req.getRequestURI().equals("/api/admin/logout")))
            audit.record(audit.actor("unknown"),res.getStatus()>=400?"REQUEST_REJECTED":"ADMIN_LOGOUT",req.getRequestURI(),res.getStatus()>=400?"FAILED":"SUCCESS","HTTP "+res.getStatus());
    }
}
