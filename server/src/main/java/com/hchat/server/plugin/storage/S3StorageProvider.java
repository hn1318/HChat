package com.hchat.server.plugin.storage;

import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** S3 / MinIO 存储（STORAGE_DRIVER=s3 时启用）。 */
@Component
@ConditionalOnProperty(name = "hchat.storage.driver", havingValue = "s3")
public class S3StorageProvider implements StorageProvider {

    private final String bucket;
    private final String publicBaseUrl;
    private final S3Client client;

    public S3StorageProvider(HChatProperties props) {
        HChatProperties.Storage storage = props.getStorage();
        if (storage.getS3Endpoint() == null || storage.getS3Endpoint().isBlank()) {
            throw new IllegalStateException("STORAGE_DRIVER=s3 时必须配置 S3_ENDPOINT");
        }
        this.bucket = storage.getS3Bucket();
        this.publicBaseUrl = storage.getS3Endpoint().replaceAll("/+$", "") + "/" + bucket;
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(storage.isS3PathStyle())
                .build();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(storage.getS3Endpoint()))
                .region(Region.of(storage.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getS3AccessKey(), storage.getS3SecretKey())))
                .serviceConfiguration(serviceConfig)
                .build();
    }

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public StoredObject put(String key, byte[] data, String contentType) {
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "S3 上传失败: " + e.getMessage());
        }
        return new StoredObject(key, publicBaseUrl + "/" + key, data.length);
    }

    @Override
    public byte[] get(String key) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "对象不存在: " + key);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR, "S3 删除失败: " + e.getMessage());
        }
    }
}
