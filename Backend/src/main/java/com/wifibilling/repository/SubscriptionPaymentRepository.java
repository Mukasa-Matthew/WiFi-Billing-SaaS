package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {
    List<SubscriptionPayment> findAllBySubscriptionIdOrderByPaidAtDesc(Long subscriptionId);
    Optional<SubscriptionPayment> findByRecordRef(String recordRef);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM SubscriptionPayment p WHERE p.invoice.id = :invoiceId")
    long sumByInvoiceId(Long invoiceId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM SubscriptionPayment p WHERE p.paidAt >= :since")
    long sumSince(OffsetDateTime since);
}
