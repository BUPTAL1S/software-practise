package com.lab.sipim.service;
import com.lab.sipim.domain.User;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class AccountManagement {
    private final UserRepository users;private final LoginTokens tokens;private final AdminAudit audit;private final IdentityReservations reservations;
    public AccountManagement(UserRepository users,LoginTokens tokens,AdminAudit audit,IdentityReservations reservations){this.users=users;this.tokens=tokens;this.audit=audit;this.reservations=reservations;}
    @Transactional public Map<String,Object> state(Long id,boolean disabled,String reason,String actor) {
        User user=users.findById(id).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"用户不存在"));
        if("ADMIN".equalsIgnoreCase(user.getRole()) || user.getUsername().equals(actor))throw new ResponseStatusException(CONFLICT,"不能禁用当前账号或管理员账号");
        if(reason!=null && reason.length()>200)throw new IllegalArgumentException("原因不能超过200字");
        String disabledAt = disabled ? Instant.now().toString() : null;
        if (users.updateAccountState(id, disabled, disabledAt, disabled ? reason : null) > 0) {
            reservations.reserve(user.getUsername(),"account");reservations.reserve(user.getSipId(),"account");
            audit.record(actor,disabled?"USER_DISABLE":"USER_RESTORE",user.getUsername(),"SUCCESS","account state changed");
        }
        // Also revoke on restore: a racing request must not reactivate an old token.
        tokens.revokeUser(id);
        return Map.of("success",true,"id",id,"username",user.getUsername(),"disabled",disabled);
    }
}
