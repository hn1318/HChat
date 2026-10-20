package com.hchat.server.plugin.storage;

import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 默认存储：本地磁盘，通过 {@code /files/**} 静态映射对外提供访问。 */
@Component
@ConditionalOnProperty(name = "hchat.storage.driver", havingValue = "local", matchIfMissing = true)
public class LocalStorageProvider implements StorageProvider {

    private final Path root;
    private final String baseUrl;

    public LocalStorageProvider(HChatProperties props) {
        this.root = Paths.get(props.getStorage().getLocalDir()).toAbsolutePath().normalize();
        this.baseUrl = props.getPublicBaseUrl().replaceAll("/+$", "");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建本地存储目录: " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public StoredObject put(String key, byte[] data, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "写入本地存储失败: " + e.getMessage());
        }
        return new StoredObject(key, baseUrl + "/files/" + key, data.length);
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "文件不存在: " + key);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "删除本地文件失败: " + e.getMessage());
        }
    }

    /** 防目录穿越：解析后的绝对路径必须仍在根目录下。 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "非法的存储 key");
        }
        return target;
    }
}
