package com.lab.sipim.web;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.RoomManagement;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/workbench/rooms")
public class RoomManagementController {
    private final RoomManagement rooms;private final LoginTokens tokens;private final UserRepository users;
    public RoomManagementController(RoomManagement rooms,LoginTokens tokens,UserRepository users){this.rooms=rooms;this.tokens=tokens;this.users=users;}
    public record Update(String name,Boolean archived){}
    @PatchMapping("/{id}/state") public Map<String,Object> update(@PathVariable Long id,@RequestBody Update request,@RequestHeader("Authorization") String auth){
        String actor=tokens.userIdOf(MeController.bearerToken(auth)).flatMap(users::findById).orElseThrow().getUsername();
        return rooms.update(id,request.name(),request.archived(),actor);
    }
}
