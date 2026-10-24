package com.wifibilling.controller;
import com.wifibilling.repository.TransactionRepository;import com.wifibilling.security.TenantPrincipal;import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.transaction.annotation.Transactional;
import java.util.*;import java.math.BigDecimal;import java.time.OffsetDateTime;
@RestController @RequestMapping("/api/finance/payments") @RequiredArgsConstructor
public class TenantPaymentsController {
 private final TransactionRepository transactions;
 public record Payment(Long id,String reference,String method,BigDecimal amount,String status,String phone,String voucher,String packageName,String router,OffsetDateTime createdAt,String notes){}
 @GetMapping @Transactional(readOnly=true) public List<Payment> list(@AuthenticationPrincipal TenantPrincipal p){return transactions.findTop1000ByTenantIdOrderByCreatedAtDesc(p.getTenantId()).stream().map(t->new Payment(t.getId(),t.getReference(),t.getNetwork(),t.getAmount(),t.getStatus(),t.getPhone(),t.getVoucherCode(),t.getPackagePlan().getName(),t.getRouter().getName(),t.getCreatedAt(),t.getNotes())).toList();}
}
