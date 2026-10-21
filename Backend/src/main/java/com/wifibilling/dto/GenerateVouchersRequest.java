package com.wifibilling.dto;
import jakarta.validation.constraints.*;
public record GenerateVouchersRequest(
 @NotNull Long packageId, @NotNull @Min(1) @Max(500) Integer count,
 @Pattern(regexp="[A-Za-z]?") String prefix,
 @Min(5) @Max(12) Integer codeLength,
 @Pattern(regexp="ALPHANUMERIC|NUMERIC|LETTERS") String codeFormat,
 @Size(max=80) String batchReference, Boolean giveaway) {
 public GenerateVouchersRequest(Long packageId, Integer count) { this(packageId,count,null,null,null,null,false); }
}
