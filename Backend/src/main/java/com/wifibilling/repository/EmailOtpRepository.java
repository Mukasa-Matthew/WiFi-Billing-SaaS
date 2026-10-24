package com.wifibilling.repository;

import com.wifibilling.model.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    Optional<EmailOtp> findFirstByEmailAndPurposeOrderByIdDesc(String email, String purpose);
}
