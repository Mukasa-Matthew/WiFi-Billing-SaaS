package com.wifibilling.controller;
import com.wifibilling.model.*;import com.wifibilling.repository.*;import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.RouterManagementService;
import com.wifibilling.util.Ipv4Subnet;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;import java.math.BigDecimal;import java.util.*;
@RestController @RequestMapping("/api/bindings") @RequiredArgsConstructor
public class IpBindingController {
 private final IpBindingRepository bindings;private final RouterRepository routers;private final PackagePlanRepository plans;private final TransactionRepository payments;private final RouterManagementService management;
 public record Request(@NotNull Long routerId,@NotBlank @Size(max=120) String name,Long packageId,
  @NotBlank @Pattern(regexp="(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") String mac,@Size(max=15) String address,
  @Future OffsetDateTime expiresAt,boolean recordPayment,@DecimalMin("0.01") BigDecimal amount,
  @Pattern(regexp="CASH|MTN|AIRTEL|BANK|OTHER") String method,@Size(max=128) String reference,@Size(max=1000) String notes) {}
 public record Response(Long id,Long routerId,String router,String name,String mac,String address,String packageName,OffsetDateTime expiresAt,String status,boolean synced) {}
 @GetMapping @Transactional(readOnly=true) public List<Response> list(@AuthenticationPrincipal TenantPrincipal p){return bindings.findAllByTenantIdOrderByCreatedAtDesc(p.getTenantId()).stream().map(v->{boolean active=!Boolean.TRUE.equals(v.getRemoved())&&(v.getExpiresAt()==null||v.getExpiresAt().isAfter(OffsetDateTime.now()));return new Response(v.getId(),v.getRouter().getId(),v.getRouter().getName(),v.getName(),v.getMac(),v.getAddress(),v.getPackagePlan()==null?null:v.getPackagePlan().getName(),v.getExpiresAt(),Boolean.TRUE.equals(v.getRemoved())?"REMOVED":active?"ACTIVE":"EXPIRED",management.bindingsSynced(v.getRouter()));}).toList();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional public Map<String,Long> create(@AuthenticationPrincipal TenantPrincipal p,@Valid @RequestBody Request req){
  Router r=routers.findByIdAndTenantId(req.routerId(),p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Router not found"));
  String address=req.address()==null||req.address().isBlank()?null:req.address();
  if(address!=null&&(!Ipv4Subnet.isIpv4(address)||!Ipv4Subnet.parse("10.10.0.0/24").contains(address)||address.equals("10.10.0.0")||address.equals("10.10.0.1")||address.equals("10.10.0.255")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Address must be a customer address in 10.10.0.0/24");
  var plan=req.packageId()==null?null:plans.findByIdAndTenantId(req.packageId(),p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Package not found"));
  if(bindings.findAllByRouterId(r.getId()).stream().anyMatch(v->!Boolean.TRUE.equals(v.getRemoved())&&(v.getExpiresAt()==null||v.getExpiresAt().isAfter(OffsetDateTime.now()))&&(v.getMac().equalsIgnoreCase(req.mac())||(address!=null&&address.equals(v.getAddress())))))throw new ResponseStatusException(HttpStatus.CONFLICT,"An active binding already uses this MAC or address");
  var expiry=req.expiresAt()!=null?req.expiresAt():plan==null?null:OffsetDateTime.now().plusSeconds(plan.getSessionTimeout());
  var binding=bindings.save(IpBinding.builder().tenant(r.getTenant()).router(r).name(req.name().trim()).mac(req.mac().toUpperCase()).address(address).packagePlan(plan).expiresAt(expiry).build());
  if(req.recordPayment()){
   if(plan==null||req.amount()==null||req.method()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Package, amount and payment method are required");
   payments.save(Transaction.builder().tenant(r.getTenant()).router(r).packagePlan(plan).reference("BIND-"+UUID.randomUUID()).phone("MANUAL").amount(req.amount()).network(req.method()).status("SUCCESSFUL").externalReference(req.reference()).notes(req.notes()).completedAt(OffsetDateTime.now()).build());
  }
  return Map.of("id",binding.getId());
 }
 @DeleteMapping("/{id}") @Transactional public void remove(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id){var v=bindings.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));v.setRemoved(true);bindings.save(v);}
}
