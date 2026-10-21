package com.wifibilling.controller;
import com.wifibilling.repository.*;
import com.wifibilling.model.*;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
@RestController @RequestMapping("/api/users") @RequiredArgsConstructor
public class UserDirectoryController {
 private final VoucherRepository vouchers; private final RadAcctRepository accounting; private final RouterRepository routers;
 public record User(Long id,Long customerId,String customerName,String phone,String voucher,String packageName,
  String status,boolean online,String ip,String mac,String router,Integer deviceLimit,long devices,
  OffsetDateTime firstLogin,OffsetDateTime expiresAt,long dataBytes,Long timeLeftSeconds) {}
 @GetMapping @Transactional(readOnly=true)
 public List<User> list(@AuthenticationPrincipal TenantPrincipal p){
  var vs=vouchers.findAllByTenantIdOrderByCreatedAtDesc(p.getTenantId());
  if(vs.isEmpty())return List.of();
  var byCode=accounting.findAllByUsernameIn(vs.stream().map(Voucher::getCode).toList()).stream().collect(Collectors.groupingBy(RadAcct::getUsername));
  var rs=routers.findAllByTenantId(p.getTenantId()).stream().filter(r->r.getWgIp()!=null).collect(Collectors.toMap(Router::getWgIp,r->r,(a,b)->a));
  OffsetDateTime now=OffsetDateTime.now();
  return vs.stream().filter(v->!Boolean.TRUE.equals(v.getArchived())).map(v->{
   var records=byCode.getOrDefault(v.getCode(),List.of());
   var latest=records.stream().max(Comparator.comparing(a->a.getAcctStartTime()==null?OffsetDateTime.MIN:a.getAcctStartTime())).orElse(null);
   long devices=records.stream().filter(a->a.getAcctStopTime()==null).filter(a->{var at=a.getAcctUpdateTime()!=null?a.getAcctUpdateTime():a.getAcctStartTime();return at!=null&&at.isAfter(now.minusMinutes(6));}).count();
   OffsetDateTime first=v.getFirstLogin();
   if(first==null)first=records.stream().map(RadAcct::getAcctStartTime).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
   OffsetDateTime expiry=v.getExpiresAt();if(expiry==null&&first!=null)expiry=first.plusSeconds(v.getPackagePlan().getSessionTimeout());
   String status= "revoked".equals(v.getStatus())?"revoked":expiry!=null&&!expiry.isAfter(now)?"expired":first!=null?"active":v.getStatus();
   Router router=latest==null?null:rs.get(latest.getNasIpAddress());
   long data=records.stream().mapToLong(a->(a.getAcctInputOctets()==null?0:a.getAcctInputOctets())+(a.getAcctOutputOctets()==null?0:a.getAcctOutputOctets())).sum();
   return new User(v.getId(),v.getCustomer()==null?null:v.getCustomer().getId(),v.getCustomer()==null?null:v.getCustomer().getName(),v.getCustomer()==null?null:v.getCustomer().getPhone(),v.getCode(),v.getPackagePlan().getName(),status,devices>0,latest==null?null:latest.getFramedIpAddress(),latest==null?v.getClaimedMac():latest.getCallingStationId(),router==null?null:router.getName(),v.getPackagePlan().getSharedUsers(),devices,first,expiry,data,expiry==null?null:Math.max(0,java.time.Duration.between(now,expiry).getSeconds()));
  }).toList();
 }
}
