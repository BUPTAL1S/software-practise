package com.lab.sipim.web;
import com.lab.sipim.esl.EslEventListener;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.lab.sipim.service.WorkbenchQueries;

@RestController @RequestMapping("/api/admin/workbench")
public class AdminOperationsController {
    private final EslEventListener esl;private final JdbcTemplate jdbc;private final CdrController cdr;
    private final Instant started=Instant.now();
    public AdminOperationsController(EslEventListener esl,JdbcTemplate jdbc,CdrController cdr){this.esl=esl;this.jdbc=jdbc;this.cdr=cdr;}
    @GetMapping("/operations") public Map<String,Object> operations() {
        Properties build=new Properties();try(var in=getClass().getResourceAsStream("/build-info.properties")){if(in!=null)build.load(in);}catch(java.io.IOException ignored){}
        Map<String,Object> result=new LinkedHashMap<>();result.put("version",build.getProperty("version","development"));
        result.put("revision",build.getProperty("revision","development"));result.put("startedAt",started);
        result.put("uptimeSeconds",Math.max(0,java.time.Duration.between(started,Instant.now()).getSeconds()));
        result.put("esl",esl.diagnostics());result.put("globalPeakRoomMembers",cdr.getStats().getBody().get("peakRoomMembers"));
        result.put("schemaVersion",jdbc.queryForObject("SELECT coalesce(max(version),0) FROM schema_versions",Long.class));
        result.put("reservedHistoricalIdentities",jdbc.queryForObject("SELECT count(*) FROM identity_reservations WHERE source='history'",Long.class));
        return result;
    }
    @GetMapping("/audit/page") public Map<String,Object> audit(@RequestParam Map<String,String> params) {
        int page=integer(params,"page",0,100000),size=integer(params,"size",25,100);
        if(size<1)throw new IllegalArgumentException("Invalid size");
        long snapshot=Long.parseLong(params.getOrDefault("snapshotMaxId","0"));if(snapshot<0)throw new IllegalArgumentException("Invalid snapshot");
        if(snapshot==0)snapshot=jdbc.queryForObject("SELECT coalesce(max(id),0) FROM audit_events",Long.class);
        List<Object> args=new ArrayList<>();args.add(snapshot);StringBuilder where=new StringBuilder(" WHERE id<=?");
        for(String key:List.of("actor","action","result"))if(params.containsKey(key)&&!params.get(key).isBlank()){where.append(" AND ").append(key).append("=?");args.add(params.get(key).trim());}
        var from=WorkbenchQueries.instant(params.get("fromTime"));var until=WorkbenchQueries.instant(params.get("untilTime"));
        if(from!=null && until!=null && !from.isBefore(until))throw new IllegalArgumentException("开始时间必须早于结束时间");
        if(from!=null){where.append(" AND julianday(occurred_at)>=julianday(?)");args.add(from.toString());}
        if(until!=null){where.append(" AND julianday(occurred_at)<julianday(?)");args.add(until.toString());}
        long total=jdbc.queryForObject("SELECT count(*) FROM audit_events"+where,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact(page,size));
        var rows=jdbc.queryForList("SELECT id,occurred_at,actor,action,target,result,detail FROM audit_events"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray());
        return Map.of("items",rows,"total",total,"page",page,"size",size,"hasNext",((long)page+1)*size<total,"snapshotMaxId",snapshot);
    }
    private static int integer(Map<String,String> params,String key,int fallback,int max) {try{int n=Integer.parseInt(params.getOrDefault(key,""+fallback));if(n<0||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid "+key);}}
}
