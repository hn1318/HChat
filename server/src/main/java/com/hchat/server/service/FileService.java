package com.hchat.server.service;

import com.hchat.server.api.dto.ResourceDtos.UploadResult;
import com.hchat.server.auth.ChatPrincipal;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.IdGenerator;
import com.hchat.server.common.Times;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.FileEntity;
import com.hchat.server.mapper.FileEntityMapper;
import com.hchat.server.plugin.storage.StorageProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 文件入库：校验 → 存储插件落盘 → 记录 files 表 → 返回可访问 URL。 */
@Service
public class FileService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM")
            .withZone(ZoneOffset.UTC);

    private static final List<String> ALLOWED_MIME_PREFIXES = List.of("image/", "audio/", "video/", "text/plain");
    private static final List<String> ALLOWED_MIME_TYPES = List.of(
            "application/pdf", "application/zip", "application/json",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final StorageProvider storage;
    private final FileEntityMapper fileMapper;
    private final IdGenerator ids;
    private final HChatProperties props;

    public FileService(StorageProvider storage, FileEntityMapper fileMapper, IdGenerator ids,
                       HChatProperties props) {
        this.storage = storage;
        this.fileMapper = fileMapper;
        this.ids = ids;
        this.props = props;
    }

    public UploadResult upload(ChatPrincipal principal, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "文件为空");
        }
        long max = props.getLimits().getMaxFileBytes();
        if (file.getSize() > max) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过 " + (max / 1024 / 1024) + "MB");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (!isAllowed(contentType)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE, "不支持的文件类型: " + contentType);
        }
        String originalName = sanitizeName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String key = principal.tenantId() + "/" + principal.appId() + "/" + MONTH.format(Times.now()) + "/"
                + UUID.randomUUID().toString().replace("-", "") + extension;
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "无法读取上传内容");
        }
        StorageProvider.StoredObject stored = storage.put(key, data, contentType);

        FileEntity entity = new FileEntity();
        entity.setId(ids.nextId());
        entity.setTenantId(principal.tenantId());
        entity.setAppId(principal.appId());
        entity.setStorageKey(stored.key());
        entity.setUrl(stored.url());
        entity.setFileName(originalName);
        entity.setMimeType(contentType);
        entity.setFileSize(stored.size());
        entity.setUploaderId(principal.userId());
        entity.setCreatedAt(Times.now());
        fileMapper.insert(entity);

        return new UploadResult(String.valueOf(entity.getId()), stored.url(), originalName, contentType, stored.size());
    }

    private boolean isAllowed(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        return ALLOWED_MIME_TYPES.contains(lower)
                || ALLOWED_MIME_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String base = name.replaceAll("[\\\\/]+", "_").replaceAll("[\\p{Cntrl}]", "").trim();
        return base.isEmpty() ? "file" : (base.length() > 200 ? base.substring(base.length() - 200) : base);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : "";
    }
}
