package com.byyourside.backend.report;

import com.byyourside.backend.post.PostRepository;
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
    private final PostRepository postRepository;

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
        // Respuesta a QUIEN REPORTA: nunca con el autor del target (V20).
        return toResponse(report, false);
    }

    public Page<ReportResponse> getPendingQueue(Pageable pageable) {
        return reportRepository.findPendingQueuePrioritized(pageable)
                .map(report -> toResponse(report, true));
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
        return toResponse(report, true);
    }

    // forModeration=true solo desde las rutas con @PreAuthorize MODERATOR/ADMIN.
    // Una query por reporte de tipo POST: la cola pagina de a 20, aceptable.
    private ReportResponse toResponse(Report report, boolean forModeration) {
        UUID targetAuthorId = forModeration && report.getTargetType() == ReportTargetType.POST
                ? postRepository.findById(report.getTargetId()).map(post -> post.getAuthor().getId()).orElse(null)
                : null;

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
                report.getReviewedAt(),
                targetAuthorId
        );
    }
}