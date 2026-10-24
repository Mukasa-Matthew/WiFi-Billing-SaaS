package com.wifibilling.service;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.config.PlatformProperties;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.wifibilling.service.RouterScriptGenerator.q;
@Service @RequiredArgsConstructor
public class RouterManagementService {
 private final RouterRepository routers; private final IpBindingRepository bindings; private final RouterTaskRepository tasks; private final PlatformProperties props;
 private boolean active(IpBinding v){return !Boolean.TRUE.equals(v.getRemoved())&&(v.getExpiresAt()==null||v.getExpiresAt().isAfter(OffsetDateTime.now()));}
 private String hash(Router r){
  String data=bindings.findAllByRouterId(r.getId()).stream().filter(this::active).sorted(Comparator.comparing(IpBinding::getId)).map(v->v.getId()+":"+v.getMac()+":"+v.getAddress()+":"+v.getExpiresAt()+":"+(v.getPackagePlan()==null?"":v.getPackagePlan().getRateLimit())).reduce("",(a,b)->a+"|"+b);
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
 }
 public boolean bindingsSynced(Router r){return hash(r).equals(r.getBindingsHash());}
 public Router resolve(String token){if(!RouterTokens.isValid(token))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return routers.findByToken(token).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}

 // Push model: the backend SSHes to the WireGuard hub (same channel used for
 // peer management) and curls the router's REST API over the tunnel, running
 // wb-pkg-sync immediately — package changes reach the portal in seconds.
 // Falls back silently to the 1-minute router poll if the push fails.
 public void triggerPortalSync(Router r) {
  try {
   String ip = com.wifibilling.util.Ipv4Subnet.stripPrefix(r.getWgIp());
   String sshTarget = props.getPeerManagementSshTarget();
   if (ip == null || ip.isBlank() || sshTarget == null || sshTarget.isBlank()) return;
   String remote = "curl -sS --max-time 4 -u " + shellQuote("wb-api:" + r.getRadiusSecret())
       + " -X POST http://" + ip + "/rest/system/script/run"
       + " -H 'Content-Type: application/json'"
       + " -d '{\"numbers\":\"wb-pkg-sync\"}'";
   List<String> cmd = List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
       "-o", "StrictHostKeyChecking=accept-new", sshTarget, remote);
   Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
   if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
  } catch (Exception ignored) {}
 }
 private static String shellQuote(String s) {
  return "'" + s.replace("'", "'\\''") + "'";
 }
 private String base(Router r){return props.getPublicBaseUrl()+"/api/router/provision/"+r.getToken();}
 @Transactional public String installer(String token){var r=resolve(token);r.setBindingsHash(null);routers.save(r);return RouterScriptGenerator.managementInstaller(props.getPublicBaseUrl(),token);}
 @Transactional public void acknowledgeBindings(String token,String hash){var r=resolve(token);if(hash(r).equals(hash)){r.setBindingsHash(hash);r.setBindingsSyncedAt(OffsetDateTime.now());routers.save(r);}}
 @Transactional public void acknowledgeTask(String token,Long id,boolean success){var r=resolve(token);var t=tasks.findByIdAndRouterId(id,r.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if("PENDING".equals(t.getStatus())){t.setStatus(success?"APPLIED":"FAILED");t.setAppliedAt(OffsetDateTime.now());tasks.save(t);}}
 @Transactional(readOnly=true) public String script(String token){
  Router r=resolve(token);StringBuilder s=new StringBuilder("# WiFi Billing management sync\n");
  if(!bindingsSynced(r)){
   String hash=hash(r);s.append(":do {\n/ip/hotspot/ip-binding/remove [find where comment~\"^wb-binding:\"]\n/queue/simple/remove [find where name~\"^wb-binding:\"]\n/system/scheduler/remove [find where name~\"^wb-bind-exp-\"]\n");
   for(var v:bindings.findAllByRouterId(r.getId())){
    String tag="wb-binding:"+v.getId();String mac=q(v.getMac());
    if(!active(v)){s.append("/ip/hotspot/active/remove [find mac-address=").append(mac).append("]\n");continue;}

    if(v.getPackagePlan()!=null){
     s.append(":local boundIp ").append(q(v.getAddress()==null?"":v.getAddress())).append('\n');
     s.append(":if ($boundIp = \"\") do={ :local lease [/ip/dhcp-server/lease find mac-address=").append(mac).append(" status=bound]; :if ([:len $lease] > 0) do={ :set boundIp [/ip/dhcp-server/lease get [:pick $lease 0] address] } }\n");
     s.append(":if ($boundIp = \"\") do={ :error \"Binding speed limit requires a current DHCP lease or explicit address\" }\n");
     s.append("/queue/simple/add name=").append(q(tag)).append(" target=($boundIp . \"/32\") max-limit=").append(q(v.getPackagePlan().getRateLimit())).append('\n');
    }
    if(v.getExpiresAt()!=null){
     String removal="/ip/hotspot/ip-binding/remove [find comment="+q(tag)+"]; /queue/simple/remove [find name="+q(tag)+"]; /ip/hotspot/active/remove [find mac-address="+mac+"]";
     var exp=v.getExpiresAt().withOffsetSameInstant(ZoneOffset.UTC);
     s.append("/system/scheduler/add name=").append(q("wb-bind-exp-"+v.getId())).append(" start-date=").append(exp.toLocalDate()).append(" start-time=").append(exp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append(" interval=0s policy=read,write,test on-event=").append(q(removal)).append('\n');
    }
    s.append("/ip/hotspot/ip-binding/add type=bypassed mac-address=").append(mac).append(" comment=").append(q(tag));
    if(v.getAddress()!=null)s.append(" address=").append(q(v.getAddress()));
    s.append('\n');
   }
   s.append("/tool/fetch url=").append(q(base(r)+"/bindings-ack?hash="+hash)).append(" http-method=post output=none\n} on-error={ :log error \"wifi-billing: binding sync failed\" }\n");
  }
  tasks.findFirstByRouterIdAndStatusOrderByIdAsc(r.getId(),"PENDING").ifPresent(t->{
   s.append(":do {\n").append("SYNC_TIME".equals(t.getAction()) ? timeScript() : t.getScript()).append("\n/tool/fetch url=").append(q(base(r)+"/tasks/"+t.getId()+"/ack?success=true")).append(" http-method=post output=none\n} on-error={ /tool/fetch url=").append(q(base(r)+"/tasks/"+t.getId()+"/ack?success=false")).append(" http-method=post output=none }\n");
  });
  return s.toString();
 }
 @Transactional public void enqueueDisconnect(Router router,String username) {
  String script="/ip/hotspot/active/remove [find user="+q(username)+"]";
  boolean pending=tasks.findAllByRouterIdOrderByCreatedAtDesc(router.getId()).stream()
      .anyMatch(t->"PENDING".equals(t.getStatus())&&script.equals(t.getScript()));
  if(!pending)tasks.save(RouterTask.builder().router(router).action("DISCONNECT").script(script).build());
 }
 private String timeScript() {
  var now=OffsetDateTime.now(ZoneOffset.UTC);
  return "/system/clock/set time-zone-autodetect=no time-zone-name=Etc/UTC date="+now.toLocalDate()+" time="+now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
 }
 @Transactional public RouterTask enqueue(Router r,String action){
  String script;
  if("SYNC_PORTAL".equals(action)){
   var s=new StringBuilder();for(String name:List.of("login.html","status.html","logout.html","alogin.html","redirect.html","rlogin.html","error.html","packages.json"))s.append("/tool/fetch url=").append(q(props.getPublicBaseUrl()+"/api/portal/templates/"+name+"?token="+r.getToken())).append(" dst-path=").append(q("flash/hotspot/"+name)).append('\n');script=s.toString();
  }else if("SYNC_TIME".equals(action)){
   script=timeScript();
  }else if("SYNC_TETHERING".equals(action)){
   String mode=r.getTenant().getTetheringProtection();
   script=RouterScriptGenerator.tetheringRules(mode==null?"OFF":mode);
  }else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown router action");
  if(tasks.findFirstByRouterIdAndStatusOrderByIdAsc(r.getId(),"PENDING").isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"A router action is already pending");
  return tasks.save(RouterTask.builder().router(r).action(action).script(script).build());
 }
}
