package com.scrimmy.client.module.basefinding;

import com.scrimmy.client.ScrimmyClient;
import com.scrimmy.client.activity.*;
import com.scrimmy.client.gui.ActivityLogScreen;
import com.scrimmy.client.gui.ActivityLogKeyScreen;
import com.google.gson.JsonObject;
import com.scrimmy.client.module.Category;
import com.scrimmy.client.module.Module;
import com.scrimmy.client.render.SoundFinderRenderer;
import com.scrimmy.client.setting.*;
import com.scrimmy.client.sound.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Local, live-only packet evidence. Never requests chunks, sends packets or registers content. */
public final class ActivityLog extends Module implements SoundFinderRenderer.Provider {
    public static final String[] CATEGORIES={"Sound", "Player", "Block", "World", "Items", "Mobs", "Item frames", "Armor stands", "Minecarts", "Boats"};
    public final BooleanSetting sounds, players, blocks, worldEvents, items, mobs, frames, stands, minecarts, boats, undergroundOnly;
    public final NumberSetting mergeRadius, mergeWindow, maxEntries, expireDays, maxFileMb, recentMinutes, maxDistance,
            panelX, panelY, panelWidth, panelHeight, panelKey;
    public final ModeSetting markerMode, minimumSeverity;
    public final ColorSetting weakColor, mediumColor, redColor;
    private final BooleanSetting yes=new BooleanSetting("yes","Yes",true), no=new BooleanSetting("no","No",false);
    private final ActivityWaypoints waypoints=new ActivityWaypoints();
    public final ActivityCapture capture=new ActivityCapture(this);
    private final Map<String,SoundTracker.Source> markerSources=new HashMap<>();
    private final Map<SoundTracker.Source,ActivityEntry> markerEntries=new IdentityHashMap<>();
    private final List<ActivityEntry> pending=new ArrayList<>();
    private final List<ActivityCluster> clusterUpdates=new ArrayList<>();
    private ActivityHistory history;
    private SoundFinderRenderer renderer;
    private ClientLevel world;
    private String server, session=UUID.randomUUID().toString();
    private boolean panelKeyDown, connected;
    private long sequence;
    private int ticks;

    public ActivityLog() {
        super("ActivityLog","Remember live positional activity across relogs",Category.BASEFINDING);
        group("capture","Capture",true);
        sounds=bool("sounds","Sounds",true); players=bool("players","Player sightings",true);
        blocks=bool("blocks","Block changes",true); worldEvents=bool("worldEvents","World events",true);
        items=bool("items","Item clusters",false); mobs=bool("mobs","Mob clusters",false);
        frames=bool("frames","Item frame clusters",false); stands=bool("stands","Armor stand clusters",false);
        minecarts=bool("minecarts","Minecart clusters",false); boats=bool("boats","Boat clusters",false);
        undergroundOnly=bool("undergroundOnly","Record underground only",false);
        group("history","History",true);
        mergeRadius=number("mergeRadius","Merge radius",8,1,32,1);
        mergeWindow=number("mergeWindow","Merge window (seconds)",30,1,600,1);
        maxEntries=number("maxEntries","Max entries",5000,100,20000,100);
        expireDays=number("expireDays","Auto-expire days (0 = never)",0,0,365,1);
        maxFileMb=number("maxFileMb","Max file size (MiB)",16,1,64,1);
        group("markers","Markers",true);
        markerMode=add(new ModeSetting("markerMode","Marker mode","Recent only","Off","Recent only","All within a distance"));
        recentMinutes=number("recentMinutes","Recent minutes",30,1,1440,1);
        maxDistance=number("maxDistance","Max render distance",256,16,1024,16);
        minimumSeverity=add(new ModeSetting("minimumSeverity","Minimum severity","Weak","Weak","Medium","Red flag"));
        weakColor=add(new ColorSetting("weakColor","Weak color",0xff819ab7));
        mediumColor=add(new ColorSetting("mediumColor","Medium color",0xffe4b878));
        redColor=add(new ColorSetting("redColor","Red flag color",0xffed7587));
        group("panel","Log panel",false);
        panelX=number("panelX","Panel X",20,0,3840,1); panelY=number("panelY","Panel Y",20,0,2160,1);
        panelWidth=number("panelWidth","Panel width",760,380,1600,10); panelHeight=number("panelHeight","Panel height",420,240,1200,10);
        panelKey=new NumberSetting("panelKey","Panel key",GLFW.GLFW_KEY_J,-1,GLFW.GLFW_KEY_LAST,1);
        panelKey.onChange(this::changed);
        add(new ActionSetting("bindPanelKey","Set panel key",()->{
            Minecraft mc=Minecraft.getInstance();mc.setScreen(new ActivityLogKeyScreen(this,mc.screen));
        }));
        add(new ActionSetting("openPanel","Open activity log",this::openPanel));
        undergroundOnly.tooltip("Only capture known below-surface positions. Unknown surface heights are excluded.");
        mergeRadius.tooltip("Merge the same category and identity within this radius, time window, dimension and session.");
        sounds.tooltip("Record all server-sent positional sounds, including unconfirmed sources in unloaded chunks.");
        players.tooltip("Record remote player spawn, movement, equipment and metadata sightings.");
        blocks.tooltip("Capture block and section-delta changes outside loaded chunks or below a known surface.");
        worldEvents.tooltip("Record positional level events, block events and explosion packets.");
        for(BooleanSetting capture : new BooleanSetting[]{items,mobs,frames,stands,minecarts,boats})
            capture.tooltip("Save centers and counts when at least two of these entities occupy the same chunk.");
        mergeWindow.tooltip("Maximum seconds between repeated sightings before starting another entry.");
        maxEntries.tooltip("Keep at most this many entries per server; prune lower scores and older ties first.");
        expireDays.tooltip("Remove entries older than this many days; zero keeps them indefinitely.");
        maxFileMb.tooltip("Maximum UTF-8 JSON file size per server; prune lower-score older entries to fit.");
        markerMode.tooltip("Draw no markers, recent sightings, or all saved sightings within render distance.");
        recentMinutes.tooltip("Age limit in minutes when the marker mode is Recent only.");
        maxDistance.tooltip("Skip marker geometry and tags beyond this many blocks from the camera.");
        minimumSeverity.tooltip("Hide lower-severity entries in the panel and world without deleting history.");
        weakColor.tooltip("Faint marker, tag and row accent for Weak evidence.");
        mediumColor.tooltip("Faint marker, tag and row accent for Medium evidence.");
        redColor.tooltip("Faint marker, tag and row accent for Red flag evidence.");
        panelX.tooltip("Saved horizontal position of the draggable activity panel.");
        panelY.tooltip("Saved vertical position of the draggable activity panel.");
        panelWidth.tooltip("Saved panel width in GUI-scaled pixels, clamped to the current screen.");
        panelHeight.tooltip("Saved panel height in GUI-scaled pixels, clamped to the current screen.");
        for(Setting<?> setting:settings()) {
            if(setting instanceof GroupSetting)setting.tooltip("Expand or collapse the "+setting.label().toLowerCase(Locale.ROOT)+" controls.");
            if(setting instanceof ActionSetting)setting.tooltip(setting.key().equals("bindPanelKey")
                    ?"Press a key to open the panel even while capture is disabled; Escape clears the binding."
                    :"Browse, filter and manage this server's saved activity.");
        }
    }
    private BooleanSetting bool(String key,String label,boolean value) { return add(new BooleanSetting(key,label,value)); }
    private NumberSetting number(String key,String label,double value,double min,double max,double step) { return add(new NumberSetting(key,label,value,min,max,step)); }
    @Override public JsonObject extraConfig() { JsonObject json=new JsonObject();json.addProperty("panelKey",panelKey.get());return json; }
    @Override public void extraConfig(JsonObject json) {
        if(json.has("panelKey"))try{panelKey.set(json.get("panelKey").getAsDouble());}catch(RuntimeException ignored){}
    }
    public void openPanel() { Minecraft mc=Minecraft.getInstance(); mc.setScreen(new ActivityLogScreen(this,mc.screen)); }
    public List<ActivityEntry> entries() { return history==null?List.of():history.snapshot(); }
    public boolean ready() { return history!=null && history.ready(); }
    public String error() { return history==null?"":history.error(); }
    public void remove(String id) { waypoints.remove(id); if (history!=null) history.remove(id); }
    public void clearEntries() { waypoints.clear(); if (history!=null) history.clear(); }
    public String activate(ActivityEntry entry) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.getConnection()!=null && mc.level!=null && entry.dimension().equals(mc.level.dimension().identifier().toString())) {
            waypoints.select(mc.getConnection().getWaypointManager(),entry,color(severity(entry.score())));
            return "Local waypoint set at "+entry.coordinates()+" (vanilla locator bar)";
        }
        mc.keyboardHandler.setClipboard(entry.coordinates());return "Copied "+entry.coordinates();
    }
    private SoundFinder finder() { return ScrimmyClient.MODULES.find(SoundFinder.class); }
    private ActivityHistory.Policy policy() {
        SoundFinder sf=finder();
        return new ActivityHistory.Policy(mergeRadius.get(),(long)(mergeWindow.get()*1000),maxEntries.get().intValue(),
                expireDays.get().intValue(),maxFileMb.get().intValue()*1024*1024,
                new SoundTracker.Weights(sf.weakWeight.get(),sf.mechanicalWeight.get(),sf.unusualWeight.get(),sf.criticalWeight.get(),
                        sf.repeatWeight.get(),sf.clusterWeight.get(),sf.sustainedWeight.get(),(long)(sf.repeatWindow.get()*1000),(long)(sf.sustainedAfter.get()*1000)));
    }
    @Override public void initialize() {
        history=new ActivityHistory(FabricLoader.getInstance().getConfigDir().resolve("scrimmy/activitylog"));
        renderer=new SoundFinderRenderer(this); renderer.initialize();
        WorldRenderEvents.END_EXTRACTION.register(renderer::extract); WorldRenderEvents.END_MAIN.register(renderer::draw);
        ClientPlayConnectionEvents.JOIN.register((handler,sender,mc)->{connected=true;connect(mc);});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->disconnect());
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((mc,level)->changeWorld(level));
        ClientTickEvents.END_CLIENT_TICK.register(this::maintenance);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc->{ drain(); history.close(); renderer.close(); });
    }
    private String server(Minecraft mc) {
        if (mc.getSingleplayerServer()!=null) return "singleplayer:"+mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        return mc.getCurrentServer()==null?null:"multiplayer:"+mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
    }
    private void connect(Minecraft mc) {
        String next=server(mc);
        if (Objects.equals(next,server) && server!=null) return;
        drain(); server=next; session=UUID.randomUUID().toString(); sequence=0;
        world=mc.level; clusterUpdates.clear(); capture.clear(); clearMarkers(); history.open(server,policy());
    }
    private void disconnect() {
        connected=false; drain(); history.open(null,policy()); server=null; world=null; pending.clear(); clusterUpdates.clear(); capture.clear(); clearMarkers();
    }
    private void changeWorld(ClientLevel next) {
        drain(); world=next; clusterUpdates.clear(); capture.clear(); clearMarkers();
        if(history!=null)history.clearClusters();
    }
    private void clearMarkers() { waypoints.clear(); markerSources.clear(); markerEntries.clear(); if (renderer!=null) renderer.clear(); }
    private void drain() {
        if (history==null) return;
        if (!pending.isEmpty()) { history.accept(List.copyOf(pending)); pending.clear(); }
        if (!clusterUpdates.isEmpty()) { history.clusters(List.copyOf(clusterUpdates)); clusterUpdates.clear(); }
    }
    private void maintenance(Minecraft mc) {
        if (mc.level!=world) changeWorld(mc.level);
        if (connected && mc.level!=null && server==null) connect(mc);
        boolean down=panelKey.get()>=GLFW.GLFW_KEY_SPACE && mc.getWindow()!=null && GLFW.glfwGetKey(mc.getWindow().handle(),panelKey.get().intValue())==GLFW.GLFW_PRESS;
        if (down && !panelKeyDown && mc.screen==null) openPanel(); panelKeyDown=down;
        if (++ticks%20==0) history.policy(policy());
        drain();
    }
    @Override public void onTick(Minecraft mc) {
        if(ticks%5==0 && mc.player!=null && world!=null)refreshMarkers(mc,System.currentTimeMillis());
    }
    @Override protected void onDisable() {
        drain(); clusterUpdates.clear(); capture.clear(); clearMarkers();
        if(history!=null) {history.clearClusters();history.flush();}
    }
    @Override protected void onSettingChanged(Setting<?> setting) {
        if(history==null)return;
        if(setting==undergroundOnly||setting==items||setting==mobs||setting==frames||setting==stands||setting==minecarts||setting==boats) {
            drain();history.clearClusters();
        }
        history.policy(policy());
    }
    public boolean active() {
        Minecraft mc=Minecraft.getInstance();
        if (!enabled() || !connected || mc.level==null) return false;
        if (world!=mc.level) changeWorld(mc.level);
        if (server==null) connect(mc);
        return server!=null;
    }
    public boolean underground(BlockPos pos) {
        if(world==null || !world.hasChunk(pos.getX()>>4,pos.getZ()>>4))return false;
        var chunk=world.getChunk(pos.getX()>>4,pos.getZ()>>4);
        return chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)
                && pos.getY()<world.getHeight(Heightmap.Types.WORLD_SURFACE,pos.getX(),pos.getZ())-1;
    }
    private void record(String category,String identity,String details,String scoringId,double x,double y,double z,boolean confirmed,int count) {
        if (!active()) return;
        boolean below=underground(BlockPos.containing(x,y,z));
        if (undergroundOnly.get()&&!below) return;
        // Bound the client-side burst buffer; the worker has a second, separately bounded queue.
        if (pending.size()>=16384) { drain(); }
        pending.add(new ActivityEntry(session+":"+(++sequence),session,world.dimension().identifier().toString(),category,identity,details,scoringId,
                x,y,z,System.currentTimeMillis(),System.currentTimeMillis(),1,below,confirmed,count,0));
    }
    public void sound(double x,double y,double z,String id,String source) {
        if (!active() || !sounds.get()) return;
        SoundTracker.Source evidence=SoundTracker.historical(BlockPos.containing(x,y,z),System.currentTimeMillis());
        evidence.soundId=id; SoundCapture.confirm(world,evidence);
        record("Sound",id,id+" · "+source+" · "+(evidence.confirmed?"confirmed ":"unconfirmed ")+evidence.blockId,id,x,y,z,evidence.confirmed,1);
    }
    public void block(BlockPos pos,net.minecraft.world.level.block.state.BlockState state) {
        if (!active() || !blocks.get()) return;
        if (world.hasChunk(pos.getX()>>4,pos.getZ()>>4) && !underground(pos)) return;
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        record("Block","block changes",id,id,pos.getX(),pos.getY(),pos.getZ(),true,1);
    }
    public void worldEvent(BlockPos pos,int type,int data) {
        if (!active() || !worldEvents.get()) return;
        String sound=SoundLevelEvents.sound(type,data);
        record("World",Integer.toString(type),"Level event "+type+" · data "+data+(sound==null?"":" · "+sound),sound==null?"level_event":sound,
                pos.getX(),pos.getY(),pos.getZ(),false,1);
    }
    public void explosion(double x,double y,double z) {
        if(active()&&worldEvents.get()) record("World","explosion","Explosion","explosion",x,y,z,false,1);
    }
    public void player(String name,UUID uuid,double x,double y,double z,String equipment) {
        if (active() && players.get() && (Minecraft.getInstance().player==null || !uuid.equals(Minecraft.getInstance().player.getUUID())))
            record("Player",uuid.toString(),name+" · "+uuid+" · "+equipment,"player",x,y,z,false,1);
    }
    public void blockEvent(BlockPos pos,net.minecraft.world.level.block.Block block,int type,int data) {
        if(active()&&worldEvents.get()) record("World","block event",BuiltInRegistries.BLOCK.getKey(block)+" · event "+type+" · data "+data,
                BuiltInRegistries.BLOCK.getKey(block).toString(),pos.getX(),pos.getY(),pos.getZ(),false,1);
    }
    public void entity(Entity entity) { if(entity!=null) entity(entity,entity.position()); }
    public void entity(Entity entity,net.minecraft.world.phys.Vec3 position) {
        if (!active() || entity==null) return;
        if (entity instanceof Player p) {
            StringBuilder gear=new StringBuilder("hands: ");
            gear.append(item(p.getMainHandItem())).append(" / ").append(item(p.getOffhandItem())).append(" · armor: ");
            for (EquipmentSlot slot:new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET})
                gear.append(item(p.getItemBySlot(slot))).append(' ');
            player(p.getName().getString(),p.getUUID(),position.x,position.y,position.z,gear.toString());
        } else cluster(entity.getId(),entity.getType(),position);
    }
    private String item(net.minecraft.world.item.ItemStack stack) { return stack.isEmpty()?"empty":BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()+" x"+stack.getCount(); }
    public boolean wantsCluster(EntityType<?> type) { return clusterCategory(type)!=null; }
    private String clusterCategory(EntityType<?> type) {
        String path=BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        String category=null;
        if(path.equals("item")) { if(items.get()) category="Items"; }
        else if(path.equals("armor_stand")) { if(stands.get()) category="Armor stands"; }
        else if(path.endsWith("item_frame")) { if(frames.get()) category="Item frames"; }
        else if(path.contains("minecart")) { if(minecarts.get()) category="Minecarts"; }
        else if(path.endsWith("boat")||path.endsWith("raft")) { if(boats.get()) category="Boats"; }
        else if(type.getCategory()!=MobCategory.MISC && mobs.get()) category="Mobs";
        return category;
    }
    public void cluster(int id,EntityType<?> type,net.minecraft.world.phys.Vec3 position) {
        if(!active())return;
        String category=clusterCategory(type);
        if(category==null)return;
        boolean below=underground(BlockPos.containing(position));
        if(undergroundOnly.get()&&!below)return;
        if(clusterUpdates.size()>=16384)drain();
        clusterUpdates.add(new ActivityCluster(session,world.dimension().identifier().toString(),id,category,position.x,position.y,position.z,below,System.currentTimeMillis(),false));
    }
    public void removeClusters(it.unimi.dsi.fastutil.ints.IntList ids) {
        if(!active())return;
        // Flush additions first so a short-lived cluster is captured before its removal.
        drain();
        for(int id:ids)clusterUpdates.add(new ActivityCluster(session,world.dimension().identifier().toString(),id,"",0,0,0,false,System.currentTimeMillis(),true));
    }
    private void refreshMarkers(Minecraft mc,long now) {
        markerEntries.clear();
        if (!enabled() || markerMode.get().equals("Off")) { markerSources.clear(); renderer.refresh(mc,now); return; }
        String dimension=world.dimension().identifier().toString();
        var camera=mc.gameRenderer.getMainCamera().position(); double range=maxDistance.get();
        List<ActivityEntry> visible=entries().stream().filter(e->e.dimension().equals(dimension) && severity(e.score())>=minimum()
                && e.distanceSquared(camera.x,camera.y,camera.z)<=range*range
                && (!markerMode.get().equals("Recent only") || now-e.lastSeen()<=recentMinutes.get()*60000))
                .sorted(Comparator.comparingDouble(e->e.distanceSquared(camera.x,camera.y,camera.z))).limit(512).toList();
        Set<String> keep=new HashSet<>();
        for(ActivityEntry e:visible) {
            keep.add(e.id());
            SoundTracker.Source s=markerSources.computeIfAbsent(e.id(),k->SoundTracker.historical(BlockPos.containing(e.x(),e.y(),e.z()),e.firstSeen()));
            s.x=e.x();s.y=e.y();s.z=e.z();s.soundId=e.scoringId();s.category=e.category();s.blockId=e.details();s.hits=e.hits();s.lastHeard=e.lastSeen();s.score=e.score();s.opacity=.35;s.confirmed=e.confirmed();
            markerEntries.put(s,e);
        }
        markerSources.keySet().retainAll(keep);
        renderer.refresh(mc,now);
    }
    @Override public String[] labelLines(SoundTracker.Source s,int severity,long now,double distance) {
        ActivityEntry e=markerEntries.get(s);
        if(e==null) return SoundFinderRenderer.Provider.super.labelLines(s,severity,now,distance);
        return new String[]{e.category()+" · "+SoundFinder.label(severity)+(e.underground()?" · underground":""),e.details(),
                e.dimension()+" · session "+e.session().substring(0,8),e.hits()+" hits · "+Math.max(0,(now-e.lastSeen())/1000)+"s ago · "+Math.round(distance)+"m",e.coordinates()};
    }
    @Override public String hudId(){return "activity_log";}
    @Override public ClientLevel world(){return world;}
    @Override public Collection<SoundTracker.Source> blocks(){return markerSources.values();}
    @Override public Collection<SoundTracker.Source> entities(){return List.of();}
    @Override public Collection<SoundTracker.Chunk> chunks(){return List.of();}
    @Override public int severity(double score){return finder().severity(score);}
    @Override public int minimum(){return minimumSeverity.get().equals("Red flag")?2:minimumSeverity.get().equals("Medium")?1:0;}
    @Override public int color(int severity){return severity==2?redColor.get():severity==1?mediumColor.get():weakColor.get();}
    @Override public NumberSetting maxDistance(){return maxDistance;}
    @Override public ModeSetting nametagMode(){return finder().nametagMode;}
    @Override public NumberSetting angle(){return finder().angle;}
    @Override public NumberSetting hysteresisMargin(){return finder().hysteresisMargin;}
    @Override public BooleanSetting softFill(){return no;}
    @Override public BooleanSetting chunkHighlight(){return no;}
    @Override public NumberSetting chunkHeight(){return finder().chunkHeight;}
    @Override public NumberSetting animationIn(){return finder().animationIn;}
    @Override public NumberSetting animationOut(){return finder().animationOut;}
    @Override public NumberSetting lingerTime(){return finder().lingerTime;}
    @Override public NumberSetting distanceFade(){return finder().distanceFade;}
    @Override public ModeSetting animationStyle(){return finder().animationStyle;}
    @Override public BooleanSetting redFlagPulse(){return finder().redFlagPulse;}
    @Override public BooleanSetting throughWalls(){return yes;}
}
