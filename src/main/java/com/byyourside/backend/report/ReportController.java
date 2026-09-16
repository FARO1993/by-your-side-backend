package com.byyourside.backend.report;

import com.byyourside.backend.report.dto.CreateReportRequest;
import com.byyourside.backend.report.dto.ReportResponse;
import com.byyourside.backend.report.dto.ResolveReportRequest;
import com.byyourside.backend.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public ResponseEntity<ReportResponse> createReport(@AuthenticationPrincipal UserPrincipal principal,
                                                       @Valid @RequestBody CreateReportRequest request) {
        ReportResponse response = reportService.createReport(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public ResponseEntity<Page<ReportResponse>> getPendingQueue(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(reportService.getPendingQueue(pageable));
    }

    @PatchMapping("/{reportId}/resolve")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    public ResponseEntity<ReportResponse> resolveReport(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable UUID reportId,
                                                        @Valid @RequestBody ResolveReportRequest request) {
        return ResponseEntity.ok(reportService.resolveReport(principal, reportId, request));
    }
}