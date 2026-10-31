package com.lab.sipim.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Admin adapter over existing client/query handlers. ApiAccessPolicy protects every route here. */
@RestController
@RequestMapping("/api/admin/workbench")
public class AdminWorkbenchController {
    private final CdrController cdr;
    private final RoomController rooms;
    private final UserController users;
    private final FileController files;
    private final HealthController health;

    public AdminWorkbenchController(CdrController cdr, RoomController rooms, UserController users,
            FileController files, HealthController health) {
        this.cdr = cdr; this.rooms = rooms; this.users = users; this.files = files; this.health = health;
    }

    @GetMapping("/health") public Map<String, Object> health() { return health.health(); }
    @GetMapping("/stats") public ResponseEntity<?> stats() { return cdr.getStats(); }
    @GetMapping("/cdr") public ResponseEntity<?> cdr(@RequestParam(required = false) String caller,
            @RequestParam(required = false) String callee, @RequestParam(defaultValue = "100") int limit) {
        return cdr.listCdr(caller, callee, limit);
    }
    @GetMapping("/rooms") public ResponseEntity<?> rooms() { return rooms.listRooms(); }
    @PostMapping("/rooms") public ResponseEntity<?> createRoom(@RequestBody(required = false) RoomController.CreateRoomRequest request) {
        return rooms.createRoom(request);
    }
    @GetMapping("/users") public Object users() { return users.list(); }
    @GetMapping("/users/online") public Object online() { return users.online(); }
    @PostMapping("/users") public ResponseEntity<?> createUser(@RequestBody(required = false) LoginController.RegisterRequest request) {
        return users.create(request);
    }
    @DeleteMapping("/users/{id}") public ResponseEntity<?> deleteUser(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.delete(id, authorization);
    }
    @GetMapping("/files") public ResponseEntity<?> files(@RequestParam(defaultValue = "100") int limit) {
        return files.list(null, null, limit);
    }
    @GetMapping("/files/{id}") public ResponseEntity<?> file(@PathVariable Long id) { return files.download(id); }
}
