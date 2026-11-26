package com.wifibilling.controller;
import com.wifibilling.service.RouterManagementService;import com.wifibilling.repository.*;import com.wifibilling.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.ObjectMapper;import java.util.*;
@RestController @RequiredArgsConstructor
public class RouterManagementController {
 private final RouterManagementService service;private final RouterRepository routers;private final RouterTaskRepository tasks;private final ObjectMapper mapper;
 @GetMapping(value="/api/router/provision/{token}/management.rsc",produces="text/plain") public ResponseEntity<String> script(@PathVariable String token,@RequestParam(defaultValue="0") int wait){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.script(token,wait));}
 // Retired: the installer now needs a one-time MANAGEMENT setup code (RouterSetupController).
 @GetMapping(value="/api/router/provision/{token}/install-management.rsc") public ResponseEntity<String> install(@PathVariable String token){return ProvisioningController.gone();}
 @PostMapping("/api/router/provision/{token}/bindings-ack") public void ackBindings(@PathVariable String token,@RequestParam String hash){service.acknowledgeBindings(token,hash);}
 @PostMapping("/api/router/provision/{token}/tasks/{id}/ack") public void ackTask(@PathVariable String token,@PathVariable Long id,@RequestParam boolean success){service.acknowledgeTask(token,id,success);}
 /** Lease fields the dashboard (Devices tab, new binding form) actually reads; the rest of each lease is dropped. */
 static final List<String> HOST_FIELDS=List.of("mac-address","address","host-name","user","uptime","status","last-seen");
 static final int MAX_HOSTS=1000;
 @PostMapping("/api/router/provision/{token}/hosts") @Transactional public void hosts(@PathVariable String token,@RequestBody JsonNode body){
  // A busy school pushes 100+ leases (~300 chars each): the old 250-row/12k-char cap refused every SYNERGY report.
  if(!body.isArray()||body.size()>MAX_HOSTS)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  var r=service.resolve(token);r.setHostsJson(trimHosts(body).toString());r.setHostsSeenAt(java.time.OffsetDateTime.now());routers.save(r);
 }
 /** Keeps only {@link #HOST_FIELDS} of each lease, as short strings, so the stored list stays small. */
 tools.jackson.databind.node.ArrayNode trimHosts(JsonNode body){
  var out=mapper.createArrayNode();
  for(JsonNode lease:body){
   if(!lease.isObject())continue;
   var kept=mapper.createObjectNode();
   for(String f:HOST_FIELDS){JsonNode v=lease.get(f);if(v!=null&&!v.isNull()&&!v.isContainer()){String s=v.asString();kept.put(f,s.length()>128?s.substring(0,128):s);}}
   out.add(kept);
  }
  return out;
 }
 @GetMapping("/api/routers/{id}/hosts") @Transactional(readOnly=true) public Map<String,Object> hosts(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id) throws Exception{
  var r=routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  return Map.of("hosts",mapper.readTree(r.getHostsJson()==null?"[]":r.getHostsJson()),"seenAt",r.getHostsSeenAt()==null?"":r.getHostsSeenAt().toString());
 }
 public record Action(String action){} public record Task(Long id,String action,String status,java.time.OffsetDateTime createdAt){}
 @PostMapping("/api/routers/{id}/tasks") public Task action(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id,@RequestBody Action a){var r=routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));var t=service.enqueue(r,a.action());return new Task(t.getId(),t.getAction(),t.getStatus(),t.getCreatedAt());}
 @GetMapping("/api/routers/{id}/tasks") @Transactional(readOnly=true) public List<Task> tasks(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id){routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));return tasks.findAllByRouterIdOrderByCreatedAtDesc(id).stream().limit(20).map(t->new Task(t.getId(),t.getAction(),t.getStatus(),t.getCreatedAt())).toList();}
}
