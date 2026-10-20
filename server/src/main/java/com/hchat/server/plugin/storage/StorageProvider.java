package com.hchat.server.plugin.storage;

/** 对象存储插件接口：本地磁盘 / S3 / MinIO 均按此实现。 */
public interface StorageProvider {

    record StoredObject(String key, String url, long size) {}

    String name();

    StoredObject put(String key, byte[] data, String contentType);

    byte[] get(String key);

    void delete(String key);
}
