package com.wifibilling.controller;
import com.wifibilling.service.RouterManagementService;import com.wifibilling.repository.*;import com.wifibilling.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.ObjectMapper;import java.util.*;
@RestController @RequiredArgsConstructor
public class RouterManagementController {
 private final RouterManagementService service;private final RouterRepository routers;private final RouterTaskRepository tasks;private final ObjectMapper mapper;
 @GetMapping(value="/api/router/provision/{token}/management.rsc",produces="text/plain") public ResponseEntity<String> script(@PathVariable String token){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.script(token));}
 @GetMapping(value="/api/router/provision/{token}/install-management.rsc",produces="text/plain") public String install(@PathVariable String token){service.resolve(token);return service.installer(token);}
 @PostMapping("/api/router/provision/{token}/bindings-ack") public void ackBindings(@PathVariable String token,@RequestParam String hash){service.acknowledgeBindings(token,hash);}
 @PostMapping("/api/router/provision/{token}/tasks/{id}/ack") public void ackTask(@PathVariable String token,@PathVariable Long id,@RequestParam boolean success){service.acknowledgeTask(token,id,success);}
 @PostMapping("/api/router/provision/{token}/hosts") @Transactional public void hosts(@PathVariable String token,@RequestBody JsonNode body){
  if(!body.isArray()||body.size()>250||body.toString().length()>12000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  var r=service.resolve(token);r.setHostsJson(body.toString());r.setHostsSeenAt(java.time.OffsetDateTime.now());routers.save(r);
 }
 @GetMapping("/api/routers/{id}/hosts") @Transactional(readOnly=true) public Map<String,Object> hosts(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id) throws Exception{
  var r=routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  return Map.of("hosts",mapper.readTree(r.getHostsJson()==null?"[]":r.getHostsJson()),"seenAt",r.getHostsSeenAt()==null?"":r.getHostsSeenAt().toString());
 }
 public record Action(String action){} public record Task(Long id,String action,String status,java.time.OffsetDateTime createdAt){}
 @PostMapping("/api/routers/{id}/tasks") public Task action(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id,@RequestBody Action a){var r=routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));var t=service.enqueue(r,a.action());return new Task(t.getId(),t.getAction(),t.getStatus(),t.getCreatedAt());}
 @GetMapping("/api/routers/{id}/tasks") @Transactional(readOnly=true) public List<Task> tasks(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id){routers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));return tasks.findAllByRouterIdOrderByCreatedAtDesc(id).stream().limit(20).map(t->new Task(t.getId(),t.getAction(),t.getStatus(),t.getCreatedAt())).toList();}
}
