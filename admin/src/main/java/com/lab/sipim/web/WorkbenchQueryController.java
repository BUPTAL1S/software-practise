package com.lab.sipim.web;
import com.lab.sipim.service.WorkbenchQueries;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/workbench")
public class WorkbenchQueryController {
    private final WorkbenchQueries queries;
    public WorkbenchQueryController(WorkbenchQueries queries){this.queries=queries;}
    @GetMapping("/{resource}/page") public WorkbenchQueries.PageResult page(@PathVariable String resource,@RequestParam Map<String,String> params){return queries.page(resource,params);}
}
