package com.byyourside.backend.report.dto;

import com.byyourside.backend.report.ReportStatus;
import jakarta.validation.constraints.NotNull;

public record ResolveReportRequest(

        @NotNull
        ReportStatus status
) {
}