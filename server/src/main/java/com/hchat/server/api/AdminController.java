package com.hchat.server.api;

import com.hchat.server.common.Times;
import com.hchat.server.auth.CurrentUser;
import com.hchat.server.service.ArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维接口：历史消息归档导出。 */
@Tag(name = "Admin", description = "运维 / 归档")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ArchiveService archiveService;

    public AdminController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /** before 支持 ISO-8601 时间或 yyyy-MM-dd 日期（默认最近 30 天前）。 */
    public record ArchiveRequest(String before, Long conversationId, Integer limit) {}

    @Operation(summary = "导出历史消息为 JSONL（冷热分离）")
    @PostMapping("/archive")
    public Map<String, Object> archive(@RequestBody(required = false) ArchiveRequest request) {
        Instant before = parseBefore(request == null ? null : request.before());
        Long conversationId = request == null ? null : request.conversationId();
        int limit = request == null || request.limit() == null ? 10_000 : request.limit();
        return archiveService.archive(CurrentUser.require(), before, conversationId, limit);
    }

    private Instant parseBefore(String value) {
        if (value == null || value.isBlank()) {
            return Times.now().minusSeconds(30L * 24 * 3600);
        }
        String trimmed = value.trim();
        try {
            if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(trimmed);
        } catch (Exception e) {
            throw new com.hchat.server.common.ApiException(com.hchat.server.common.ErrorCode.INVALID_REQUEST,
                    "before 需为 ISO-8601 时间或 yyyy-MM-dd");
        }
    }
}
