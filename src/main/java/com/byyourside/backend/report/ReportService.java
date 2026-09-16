package com.byyourside.backend.report;

import com.byyourside.backend.report.dto.CreateReportRequest;
import com.byyourside.backend.report.dto.ReportResponse;
import com.byyourside.backend.report.dto.ResolveReportRequest;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;

    @Transactional
    public ReportResponse createReport(UserPrincipal principal, CreateReportRequest request) {
        User reporter = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Report report = Report.builder()
                .reporter(reporter)
                .targetType(request.targetType())
                .targetId(request.targetId())
                .reason(request.reason())
                .description(request.description())
                .build();

        report = reportRepository.save(report);
        return toResponse(report);
    }

    public Page<ReportResponse> getPendingQueue(Pageable pageable) {
        return reportRepository.findPendingQueuePrioritized(pageable)
                .map(this::toResponse);
    }

    @Transactional
    public ReportResponse resolveReport(UserPrincipal moderatorPrincipal, UUID reportId, ResolveReportRequest request) {
        if (request.status() == ReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot resolve a report back to PENDING");
        }

        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        if (report.getStatus() != ReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report was already reviewed");
        }

        User moderator = userRepository.findById(moderatorPrincipal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Moderator not found"));

        report.setStatus(request.status());
        report.setReviewedBy(moderator);
        report.setReviewedAt(java.time.Instant.now());

        report = reportRepository.save(report);
        return toResponse(report);
    }

    private ReportResponse toResponse(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getReporter().getId(),
                report.getTargetType().name(),
                report.getTargetId(),
                report.getReason().name(),
                report.getDescription(),
                report.getStatus().name(),
                report.getReviewedBy() != null ? report.getReviewedBy().getId() : null,
                report.getCreatedAt(),
                report.getReviewedAt()
        );
    }
}