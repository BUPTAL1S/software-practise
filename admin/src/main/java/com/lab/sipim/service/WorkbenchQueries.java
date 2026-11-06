package com.lab.sipim.service;

import com.lab.sipim.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One allowlisted query definition is shared by pages, totals and exports. */
@Service @Transactional(readOnly=true)
public class WorkbenchQueries {
    private final EntityManager em;
    public WorkbenchQueries(EntityManager em){this.em=em;}
    public record PageResult(List<Map<String,Object>> items,int page,int size,long total,boolean hasNext,long snapshotMaxId) {}
    private record Resource(Class<?> type,String time,Map<String,String> filters) {}
    private Resource resource(String name) {
        return switch(name) {
            case "cdr" -> new Resource(Cdr.class,"startedAt",Map.of("caller","caller","callee","calleeOrRoom"));
            case "files" -> new Resource(FileIndex.class,"createdAt",Map.of("from","fromUser","to","toUserOrRoom"));
            case "messages" -> new Resource(PrivateMessage.class,"createdAt",Map.of());
            case "room-messages" -> new Resource(RoomMessage.class,"createdAt",Map.of("room","roomUri","sender","sender"));
            case "users" -> new Resource(User.class,null,Map.of("username","username"));
            case "rooms" -> new Resource(Room.class,null,Map.of("uri","sipUri"));
            default -> throw new IllegalArgumentException("Unknown resource");
        };
    }
    public PageResult page(String name,Map<String,String> params) {
        Resource resource=resource(name);
        int page=integer(params,"page",0,0,100000),size=integer(params,"size",25,1,100);
        long snapshot=number(params,"snapshotMaxId",0);
        var cb=em.getCriteriaBuilder();
        if(snapshot==0) {
            var max=cb.createQuery(Long.class);var root=max.from(resource.type());
            max.select(cb.coalesce(cb.max(root.<Long>get("id")),0L)); snapshot=em.createQuery(max).getSingleResult();
        }
        var count=cb.createQuery(Long.class);var cr=count.from(resource.type());
        count.select(cb.count(cr)).where(predicates(cb,cr,resource,params,snapshot));
        long total=em.createQuery(count).getSingleResult();
        var query=cb.createQuery();var root=query.from(resource.type());
        query.select(root).where(predicates(cb,root,resource,params,snapshot)).orderBy(cb.desc(root.get("id")));
        var rows=em.createQuery(query).setFirstResult(Math.multiplyExact(page,size)).setMaxResults(size).getResultList();
        List<Map<String,Object>> items=new ArrayList<>(); for(Object row:rows)items.add(view(row));
        return new PageResult(items,page,size,total,((long)page+1)*size<total,snapshot);
    }
    public Map<String,Object> callStats(Map<String,String> params) {
        var cb=em.getCriteriaBuilder();var q=cb.createQuery(Object[].class);var root=q.from(Cdr.class);
        q.multiselect(cb.count(root),cb.coalesce(cb.sumAsLong(root.get("durationSec")),0L));
        q.where(predicates(cb,root,resource("cdr"),params,Long.MAX_VALUE));
        Object[] result=em.createQuery(q).getSingleResult();long count=((Number)result[0]).longValue(),seconds=((Number)result[1]).longValue();
        return map("totalCalls",count,"totalDurationSec",seconds,"averageDurationSec",count==0?0.0:Math.round(10.0*seconds/count)/10.0);
    }
    private Predicate[] predicates(jakarta.persistence.criteria.CriteriaBuilder cb,Root<?> root,Resource resource,Map<String,String> params,long snapshot) {
        List<Predicate> where=new ArrayList<>();where.add(cb.le(root.get("id"),snapshot));
        resource.filters().forEach((key,field)->{String value=clean(params.get(key));if(value!=null)where.add(cb.equal(root.get(field+"Key"),value.toLowerCase(Locale.ROOT)));});
        if(resource.type()==PrivateMessage.class)for(String key:List.of("user","peer")) {
            String value=clean(params.get(key));if(value!=null)where.add(cb.or(cb.equal(root.get("senderKey"),value.toLowerCase(Locale.ROOT)),cb.equal(root.get("receiverKey"),value.toLowerCase(Locale.ROOT))));
        }
        for(String state:List.of("disabled","archived"))if(params.containsKey(state)) {
            if(!Set.of("true","false").contains(params.get(state)))throw new IllegalArgumentException("Invalid "+state);
            if(state.equals("disabled") && resource.type()==User.class || state.equals("archived") && resource.type()==Room.class)where.add(cb.equal(root.get(state),Boolean.parseBoolean(params.get(state))));
        }
        Instant from=instant(params.get("fromTime")),until=instant(params.get("untilTime"));
        if(from!=null && until!=null && !from.isBefore(until))throw new IllegalArgumentException("开始时间必须早于结束时间");
        if(resource.time()!=null) {
            if(from!=null)where.add(cb.greaterThanOrEqualTo(root.get(resource.time()),from));
            if(until!=null)where.add(cb.lessThan(root.get(resource.time()),until));
        }
        return where.toArray(Predicate[]::new);
    }
    public static Instant instant(String text) {if(clean(text)==null)return null;try{return Instant.parse(text);}catch(Exception e){throw new IllegalArgumentException("时间必须包含时区，例如 2026-11-06T00:00:00+08:00");}}
    private static String clean(String value){return value==null || value.isBlank()?null:value.trim();}
    private static int integer(Map<String,String> p,String key,int fallback,int min,int max){long n=number(p,key,fallback);if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return (int)n;}
    private static long number(Map<String,String> p,String key,long fallback){try{long n=Long.parseLong(p.getOrDefault(key,""+fallback));if(n<0)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid "+key);}}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    public static Map<String,Object> view(Object value) {
        if(value instanceof Cdr c)return map("id",c.getId(),"callId",c.getCallId(),"caller",c.getCaller(),"calleeOrRoom",c.getCalleeOrRoom(),"startedAt",c.getStartedAt(),"durationSec",c.getDurationSec());
        if(value instanceof User u)return map("id",u.getId(),"username",u.getUsername(),"sipId",u.getSipId(),"nickname",u.getNickname(),"role",u.getRole(),"disabled",u.isDisabled(),"disabledReason",u.getDisabledReason(),"disabledAt",u.getDisabledAt());
        if(value instanceof Room r)return map("id",r.getId(),"name",r.getName(),"sipUri",r.getSipUri(),"maxMembers",r.getMaxMembers(),"memberCount",r.getMemberCount(),"archived",r.isArchived());
        if(value instanceof FileIndex f) {String name=Path.of(f.getPath()).getFileName().toString();int at=name.indexOf('_');return map("id",f.getId(),"name",at>0?name.substring(at+1):name,"size",f.getSize(),"from",f.getFromUser(),"to",f.getToUserOrRoom(),"createdAt",f.getCreatedAt());}
        if(value instanceof PrivateMessage m)return map("id",m.getId(),"sender",m.getSender(),"receiver",m.getReceiver(),"content",m.getContent(),"isFile",m.isFile(),"status",m.getStatus(),"createdAt",m.getCreatedAt());
        if(value instanceof RoomMessage m)return map("id",m.getId(),"roomUri",m.getRoomUri(),"sender",m.getSender(),"content",m.getContent(),"isFile",m.isFile(),"createdAt",m.getCreatedAt());
        throw new IllegalArgumentException("Unsupported entity");
    }
}
