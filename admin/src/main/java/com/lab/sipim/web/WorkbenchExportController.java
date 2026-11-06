package com.lab.sipim.web;
import com.lab.sipim.repo.UserRepository;
import com.lab.sipim.security.LoginTokens;
import com.lab.sipim.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController @RequestMapping("/api/admin/workbench")
public class WorkbenchExportController {
    private final WorkbenchQueries queries;private final AdminAudit audit;private final LoginTokens tokens;private final UserRepository users;
    public WorkbenchExportController(WorkbenchQueries queries,AdminAudit audit,LoginTokens tokens,UserRepository users){this.queries=queries;this.audit=audit;this.tokens=tokens;this.users=users;}
    @GetMapping("/query-stats") public Map<String,Object> stats(@RequestParam Map<String,String> params){return queries.callStats(params);}
    @GetMapping("/{resource}/export") public ResponseEntity<StreamingResponseBody> export(@PathVariable String resource,@RequestParam Map<String,String> params,@RequestHeader("Authorization") String auth) {
        List<String> columns=switch(resource) {
            case "cdr" -> List.of("id","callId","caller","calleeOrRoom","startedAt","durationSec");
            case "files" -> List.of("id","name","size","from","to","createdAt");
            case "messages" -> List.of("id","sender","receiver","content","isFile","status","createdAt");
            case "room-messages" -> List.of("id","roomUri","sender","content","isFile","createdAt");
            default -> throw new IllegalArgumentException("该栏目不支持导出");
        };
        Map<String,String> query=new HashMap<>(params);query.put("page","0");query.put("size","100");query.remove("snapshotMaxId");
        var first=queries.page(resource,query);
        if(first.total()>10000)throw new IllegalArgumentException("匹配记录超过10000条，请缩小筛选范围后导出");
        query.put("snapshotMaxId",""+first.snapshotMaxId());
        String actor=tokens.userIdOf(MeController.bearerToken(auth)).flatMap(users::findById).orElseThrow().getUsername();
        StreamingResponseBody body=output->{
            try {
                Writer writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));writer.write('\uFEFF');line(writer,columns);
                var page=first;
                do {
                    for(var item:page.items())line(writer,columns.stream().map(key->Objects.toString(item.get(key),"")).toList());
                    if(!page.hasNext())break;
                    query.put("page",""+(page.page()+1));page=queries.page(resource,query);
                } while(true);
                writer.flush();audit.record(actor,"DATA_EXPORT",resource,"SUCCESS","rows="+first.total());
            } catch(Exception error) {audit.record(actor,"DATA_EXPORT",resource,"FAILED","export interrupted");throw new IOException("导出失败",error);}
        };
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+resource+".csv\"").body(body);
    }
    public static String csvCell(String value) {
        String stripped=value.stripLeading();
        if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || value.startsWith("\t") || value.startsWith("\r"))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private static void line(Writer out,List<String> cells)throws IOException {out.write(String.join(",",cells.stream().map(WorkbenchExportController::csvCell).toList()));out.write("\r\n");}
}
