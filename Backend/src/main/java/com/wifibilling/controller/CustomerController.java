package com.wifibilling.controller;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.security.TenantPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@RestController @RequestMapping("/api/customers") @RequiredArgsConstructor
public class CustomerController {
 private final CustomerRepository customers; private final TenantRepository tenants;
 public record Request(@NotBlank @Size(max=120) String name,@Size(max=32) String phone,@Size(max=500) String notes) {}
 public record Response(Long id,String name,String phone,String notes) {}
 private Response dto(Customer c){return new Response(c.getId(),c.getName(),c.getPhone(),c.getNotes());}
 @GetMapping @Transactional(readOnly=true) public List<Response> list(@AuthenticationPrincipal TenantPrincipal p){return customers.findAllByTenantIdOrderByCreatedAtDesc(p.getTenantId()).stream().map(this::dto).toList();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional public Response create(@AuthenticationPrincipal TenantPrincipal p,@Valid @RequestBody Request r){
  return dto(customers.save(Customer.builder().tenant(tenants.findById(p.getTenantId()).orElseThrow()).name(r.name().trim()).phone(r.phone()).notes(r.notes()).build()));
 }
 @PutMapping("/{id}") @Transactional public Response edit(@AuthenticationPrincipal TenantPrincipal p,@PathVariable Long id,@Valid @RequestBody Request r){
  Customer c=customers.findByIdAndTenantId(id,p.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  c.setName(r.name().trim());c.setPhone(r.phone());c.setNotes(r.notes());return dto(customers.save(c));
 }
}
