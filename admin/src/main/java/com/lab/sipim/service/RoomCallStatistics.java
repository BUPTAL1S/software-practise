package com.lab.sipim.service;

import com.lab.sipim.domain.Cdr;
import com.lab.sipim.repo.CdrRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** One room-CDR read per response, rather than one query and sort per room. */
@Service
public class RoomCallStatistics {
    private final CdrRepository records;

    public RoomCallStatistics(CdrRepository records) { this.records = records; }

    private String cachedKey="";
    private Map<String,Integer> cachedPeaks=Map.of();
    public synchronized Map<String, Integer> peaksByRoom() {
        String key=records.count()+":"+records.sumTotalDurationSec()+":"+records.newestId();
        if(!key.equals(cachedKey)){cachedPeaks=Map.copyOf(calculate(records.findRoomCalls()));cachedKey=key;}
        return cachedPeaks;
    }

    public int peakForRoom(String room) { return peaksByRoom().getOrDefault(roomKey(room), 0); }

    public static String roomKey(String value) {
        if (value == null) return "";
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("sip:")) key = key.substring(4);
        int at = key.indexOf('@');
        if (at >= 0) key = key.substring(0, at);
        return key.startsWith("room") ? key : "";
    }

    public static Map<String, Integer> calculate(List<Cdr> calls) {
        Map<String, TreeMap<Instant, int[]>> timelines = new HashMap<>();
        for (Cdr call : calls) {
            String room = roomKey(call.getCalleeOrRoom());
            if (room.isEmpty() || call.getStartedAt() == null || call.getDurationSec() <= 0) continue;
            TreeMap<Instant, int[]> events = timelines.computeIfAbsent(room, ignored -> new TreeMap<>());
            events.computeIfAbsent(call.getStartedAt(), ignored -> new int[2])[0]++;
            events.computeIfAbsent(call.getStartedAt().plusSeconds(call.getDurationSec()), ignored -> new int[2])[1]++;
        }
        Map<String, Integer> peaks = new HashMap<>();
        timelines.forEach((room, timeline) -> {
            int active = 0, peak = 0;
            for (int[] change : timeline.values()) {
                // Preserve the existing inclusive-boundary rule: arrivals precede departures.
                active += change[0];
                peak = Math.max(peak, active);
                active -= change[1];
            }
            peaks.put(room, peak);
        });
        return peaks;
    }
}
