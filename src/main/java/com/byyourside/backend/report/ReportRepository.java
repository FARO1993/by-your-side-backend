package com.byyourside.backend.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    // Cola de moderacion: los reportes de SELF_HARM_RISK siempre van primero,
    // independientemente de la fecha de creacion.
    @Query("""
            SELECT r FROM Report r
            WHERE r.status = 'PENDING'
            ORDER BY CASE WHEN r.reason = 'SELF_HARM_RISK' THEN 0 ELSE 1 END, r.createdAt ASC
            """)
    Page<Report> findPendingQueuePrioritized(Pageable pageable);
}
