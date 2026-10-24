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
  OffsetDateTime now=OffsetDateTime.now();
  // Aggregated accounting: first login, total octets, live device count — one query
  record Agg(OffsetDateTime first,long data,long devices) {}
  var stats=new HashMap<String,Agg>();
  for(Object[] row:accounting.userStats(p.getTenantId(),now.minusMinutes(6)))
   stats.put((String)row[0],new Agg(row[1]==null?null:(row[1] instanceof java.sql.Timestamp ts?ts.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC):row[1] instanceof java.sql.Date d2?d2.toLocalDate().atStartOfDay().atOffset(java.time.ZoneOffset.UTC):(java.time.OffsetDateTime)row[1]),((Number)row[2]).longValue(),((Number)row[3]).longValue()));
  // Latest session per user — one DISTINCT ON query
  var latestBy=new HashMap<String,Object[]>();
  for(Object[] row:accounting.latestSessions(p.getTenantId())) latestBy.put((String)row[0],row);
  var rs=routers.findAllByTenantId(p.getTenantId()).stream().filter(r->r.getWgIp()!=null).collect(Collectors.toMap(r->com.wifibilling.util.Ipv4Subnet.stripPrefix(r.getWgIp()),r->r,(a,b)->a));
  return vs.stream().filter(v->!Boolean.TRUE.equals(v.getArchived())).map(v->{
   var agg=stats.get(v.getCode());
   var latest=latestBy.get(v.getCode());
   long devices=agg==null?0:agg.devices();
   OffsetDateTime first=v.getFirstLogin()!=null?v.getFirstLogin():(agg==null?null:agg.first());
   OffsetDateTime expiry=v.getExpiresAt();if(expiry==null&&first!=null)expiry=first.plusSeconds(v.getPackagePlan().getSessionTimeout());
   String status= "revoked".equals(v.getStatus())?"revoked":expiry!=null&&!expiry.isAfter(now)?"expired":first!=null?"active":v.getStatus();
   Router router=latest==null?null:rs.get((String)latest[3]);
   long data=agg==null?0:agg.data();
   return new User(v.getId(),v.getCustomer()==null?null:v.getCustomer().getId(),v.getCustomer()==null?null:v.getCustomer().getName(),v.getCustomer()==null?null:v.getCustomer().getPhone(),v.getCode(),v.getPackagePlan().getName(),status,devices>0,latest==null?null:(String)latest[1],latest==null?v.getClaimedMac():(String)latest[2],router==null?null:router.getName(),v.getPackagePlan().getSharedUsers(),devices,first,expiry,data,expiry==null?null:Math.max(0,java.time.Duration.between(now,expiry).getSeconds()));
  }).toList();
 }
}
