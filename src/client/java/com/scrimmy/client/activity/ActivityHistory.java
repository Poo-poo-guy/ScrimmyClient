package com.scrimmy.client.activity;

import com.google.gson.*;
import com.scrimmy.client.sound.SoundTracker;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** All merging, pruning, JSON work and disk I/O belongs to this single ordered worker. */
public final class ActivityHistory implements AutoCloseable {
    public record Policy(double radius, long window, int limit, int days, int maxBytes, SoundTracker.Weights weights) {}
    private static final Gson JSON = new GsonBuilder().create();
    private final Path directory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Scrimmy ActivityLog history"); t.setDaemon(true); return t;
    });
    private final Map<String, ActivityEntry> entries = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Long>> recent = new HashMap<>();
    private final Map<String,LinkedHashMap<String,Integer>> blockDetails=new HashMap<>();
    private final Map<Integer,ActivityCluster> clusters=new HashMap<>();
    private record Cell(String session,String dimension,String category,String identity,int x,int y,int z) {}
    private final Map<Cell,Set<String>> index=new HashMap<>();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile List<ActivityEntry> snapshot = List.of();
    private volatile String error = "";
    private volatile boolean ready;
    private volatile long requestedGeneration;
    private long generation;
    private Path file;
    private Policy policy;
    private boolean dirty, loaded;
    public ActivityHistory(Path directory) {
        this.directory=directory;
        worker.scheduleWithFixedDelay(() -> guarded(() -> { if (file != null) { prune(System.currentTimeMillis()); publish(); save(); } }), 5, 5, TimeUnit.SECONDS);
    }
    public List<ActivityEntry> snapshot() { return snapshot; }
    public String error() { return error; }
    public boolean ready() { return ready; }
    public void open(String server, Policy next) {
        long request=++requestedGeneration;
        ready=false; snapshot=List.of();
        worker.execute(() -> guarded(() -> {
            saveGuarded(); entries.clear(); recent.clear(); blockDetails.clear(); clusters.clear(); index.clear(); file=null; loaded=false; dirty=false; policy=next; generation=request;
            if (server == null) return;
            file=directory.resolve(serverKey(server)+".json");
            if (Files.exists(file)) {
                if (Files.size(file)>64L*1024*1024) throw new java.io.IOException("ActivityLog file exceeds 64 MiB safety limit");
                try (var reader=Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    ActivityEntry[] loaded=JSON.fromJson(reader, ActivityEntry[].class);
                    if (loaded == null) throw new java.io.IOException("Invalid ActivityLog file");
                    for (ActivityEntry e:loaded) if (e!=null && e.valid()) { entries.put(e.id(),e); addIndex(e); }
                }
            }
            prune(System.currentTimeMillis()); loaded=true; dirty=true; publish();
            if(generation==requestedGeneration)ready=true;
        }));
    }
    public void policy(Policy value) { worker.execute(() -> {
        if (value.equals(policy)) return;
        boolean rebuild=policy==null||policy.radius!=value.radius; policy=value;
        if (rebuild) { index.clear(); for (ActivityEntry e:entries.values()) addIndex(e); }
        if (file!=null) { prune(System.currentTimeMillis()); publish(); dirty=true; }
    }); }
    public void clearClusters() { worker.execute(clusters::clear); }
    public void clusters(List<ActivityCluster> updates) {
        if (updates.isEmpty()) return;
        if (queued.addAndGet(updates.size())>32768) { queued.addAndGet(-updates.size()); error="ActivityLog cluster queue full"; return; }
        worker.execute(() -> { try { guarded(() -> {
            if (!loaded) return;
            Set<String> changed=new HashSet<>();
            for (ActivityCluster c:updates) {
                String chunk=c.session()+":"+c.dimension()+":"+c.category()+":"+((int)Math.floor(c.x())>>4)+":"+((int)Math.floor(c.z())>>4);
                if (c.removed()) { clusters.remove(c.entityId()); continue; }
                if (clusters.size()<16384 || clusters.containsKey(c.entityId())) clusters.put(c.entityId(),c);
                changed.add(chunk);
            }
            Map<String,List<ActivityCluster>> groups=new HashMap<>();
            long now=updates.getLast().time();
            for (ActivityCluster c:clusters.values()) {
                String key=c.session()+":"+c.dimension()+":"+c.category()+":"+((int)Math.floor(c.x())>>4)+":"+((int)Math.floor(c.z())>>4);
                if(changed.contains(key))groups.computeIfAbsent(key,k->new ArrayList<>()).add(c);
            }
            for(var group:groups.values()) {
                if(group.size()<2)continue;
                double x=0,y=0,z=0; boolean below=false; long time=0;
                for(var c:group){x+=c.x();y+=c.y();z+=c.z();below|=c.underground();time=Math.max(time,c.time());}
                var c=group.getFirst();
                merge(new ActivityEntry(UUID.randomUUID().toString(),c.session(),c.dimension(),c.category(),c.category(),
                        group.size()+" "+c.category().toLowerCase(Locale.ROOT),c.category(),x/group.size(),y/group.size(),z/group.size(),time,time,1,below,false,group.size(),0));
            }
            prune(now); publish(); dirty=true;
        }); } finally { queued.addAndGet(-updates.size()); } });
    }
    public void accept(List<ActivityEntry> batch) {
        if (batch.isEmpty()) return;
        int pending=queued.addAndGet(batch.size());
        if (pending>32768) { queued.addAndGet(-batch.size()); error="Capture queue full; reduce capture categories"; return; }
        worker.execute(() -> { try { guarded(() -> {
            if (file==null || !loaded) return;
            for (ActivityEntry e:batch) merge(e);
            prune(System.currentTimeMillis()); publish(); dirty=true;
        }); } finally { queued.addAndGet(-batch.size()); } });
    }
    private void merge(ActivityEntry event) {
        ActivityEntry match=null;
        Cell center=cell(event);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            Set<String> bucket=index.get(new Cell(center.session,center.dimension,center.category,center.identity,center.x+dx,center.y+dy,center.z+dz));
            if(bucket==null)continue;
            for(String key:bucket) {
                ActivityEntry old=entries.get(key);
                if(event.lastSeen()-old.lastSeen()>policy.window || event.lastSeen()<old.lastSeen())continue;
                if(old.distanceSquared(event.x(),event.y(),event.z())<=policy.radius*policy.radius
                        && (match==null || old.lastSeen()>match.lastSeen()))match=old;
            }
        }
        String id=match==null?event.id():match.id();
        var times=recent.computeIfAbsent(id,k->new ArrayDeque<>());
        while (!times.isEmpty() && event.lastSeen()-times.peekFirst()>policy.weights.window()) times.removeFirst();
        if (times.size()==128) times.removeFirst(); times.addLast(event.lastSeen());
        long first=match==null?event.firstSeen():match.firstSeen();
        double score=SoundTracker.score(event.scoringId(),policy.weights,times.size(),event.lastSeen()-first,1);
        String details=event.details();
        if(event.category().equals("Block")) {
            var ids=blockDetails.computeIfAbsent(id,k->new LinkedHashMap<>());ids.merge(details,1,Integer::sum);
            if(ids.size()>8)ids.remove(ids.keySet().iterator().next());
            details=ids.entrySet().stream().map(e->e.getKey()+" x"+e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        }
        ActivityEntry merged=new ActivityEntry(id,event.session(),event.dimension(),event.category(),event.identity(),details,event.scoringId(),
                event.x(),event.y(),event.z(),first,event.lastSeen(),match==null?1:match.hits()+1,
                event.underground() || match!=null&&match.underground(),event.confirmed(),event.count(),score);
        if(match!=null) removeIndex(match);
        entries.put(id,merged); addIndex(merged);
    }
    private void prune(long now) {
        if (policy==null) return;
        int before=entries.size();
        if (policy.days>0) {
            var expired=entries.values().stream().filter(e->now-e.lastSeen()>policy.days*86400000L).map(ActivityEntry::id).toList();
            for(String id:expired)removeEntry(id);
        }
        prepareTypes();
        while (entries.size()>policy.limit) removeLowest();
        if(before!=entries.size())dirty=true;
        recent.keySet().retainAll(entries.keySet());
    }
    private void removeLowest() {
        ActivityEntry lowest=entries.values().stream().min(Comparator.comparingDouble(this::totalScore).thenComparingLong(ActivityEntry::lastSeen)).orElseThrow();
        removeEntry(lowest.id());
    }
    private Map<String,Set<String>> types=Map.of();
    private double totalScore(ActivityEntry e) {
        return e.score() + (e.underground()?policy.weights.unusual():0)
                + Math.max(0,types.getOrDefault(e.chunk(),Set.of()).size()-1)*policy.weights.cluster();
    }
    private Cell cell(ActivityEntry e) { return new Cell(e.session(),e.dimension(),e.category(),e.identity(),
            (int)Math.floor(e.x()/policy.radius),(int)Math.floor(e.y()/policy.radius),(int)Math.floor(e.z()/policy.radius)); }
    private void addIndex(ActivityEntry e) { index.computeIfAbsent(cell(e),k->new HashSet<>()).add(e.id()); }
    private void removeIndex(ActivityEntry e) { Cell cell=cell(e); var bucket=index.get(cell); if(bucket!=null){bucket.remove(e.id());if(bucket.isEmpty())index.remove(cell);} }
    private void removeEntry(String id) { ActivityEntry e=entries.remove(id); if(e!=null)removeIndex(e); recent.remove(id); blockDetails.remove(id); }
    private void prepareTypes() {
        Map<String,Set<String>> next=new HashMap<>();
        for (ActivityEntry e:entries.values()) next.computeIfAbsent(e.chunk(),k->new HashSet<>()).add(e.category());
        types=next;
    }
    private void publish() {
        prepareTypes();
        List<ActivityEntry> view=new ArrayList<>(entries.size());
        for (ActivityEntry e:entries.values()) view.add(new ActivityEntry(e.id(),e.session(),e.dimension(),e.category(),e.identity(),e.details(),e.scoringId(),
                e.x(),e.y(),e.z(),e.firstSeen(),e.lastSeen(),e.hits(),e.underground(),e.confirmed(),e.count(),totalScore(e)));
        if(generation==requestedGeneration)snapshot=List.copyOf(view);
    }
    public void remove(String id) { worker.execute(() -> { removeEntry(id); publish(); dirty=true; }); }
    public void clear() { worker.execute(() -> { entries.clear(); recent.clear(); blockDetails.clear(); index.clear(); clusters.clear(); publish(); dirty=true; saveGuarded(); }); }
    public CompletableFuture<Void> flush() { return CompletableFuture.runAsync(this::saveGuarded,worker); }
    private void saveGuarded() { guarded(this::save); }
    private void save() throws Exception {
        if (!dirty || file==null || !loaded) return;
        Map<String,byte[]> encoded=new LinkedHashMap<>(); long size=2;
        for(ActivityEntry e:entries.values()) { byte[] bytes=JSON.toJson(e).getBytes(StandardCharsets.UTF_8);encoded.put(e.id(),bytes);size+=bytes.length+1; }
        List<ActivityEntry> ordered=new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparingDouble(this::totalScore).thenComparingLong(ActivityEntry::lastSeen));
        int removed=0;
        while(size-(encoded.isEmpty()?0:1)>policy.maxBytes && removed<ordered.size()) {
            ActivityEntry e=ordered.get(removed++);size-=encoded.remove(e.id()).length+1;removeEntry(e.id());
        }
        var output=new java.io.ByteArrayOutputStream((int)Math.max(2,size)); output.write('[');
        boolean first=true; for(byte[] bytes:encoded.values()){if(!first)output.write(',');output.write(bytes);first=false;}output.write(']');
        byte[] data=output.toByteArray();
        publish(); Files.createDirectories(directory);
        Path temp=file.resolveSibling(file.getFileName()+".tmp"); Files.write(temp,data);
        try { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        dirty=false; error="";
    }
    public static String serverKey(String server) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8)));
    }
    private interface Work { void run() throws Exception; }
    private void guarded(Work action) { try { action.run(); } catch (Exception e) { error="ActivityLog: "+e.getClass().getSimpleName()+" — "+e.getMessage(); } }
    @Override public void close() {
        worker.execute(this::saveGuarded); worker.shutdown();
        try { if (!worker.awaitTermination(5,TimeUnit.SECONDS)) error="ActivityLog save still pending at shutdown"; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
