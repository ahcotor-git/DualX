package com.dualx.core.port;

import java.util.Optional;

/**
 * 文件存储端口（D09-①：每实例受保护本地目录 + 存储端口）。
 *
 * <p><b>只认相对路径。</b>调用方给的是 {@code maps/<mapId>/<name>.png} 这类相对路径，
 * 由适配器决定它落在哪个根目录下。这样做的直接目的是让 {@code t_map.image_path}
 * 落库的是可迁移的相对路径——库与文件一起备份到另一台机器时不必改写数据。</p>
 *
 * <p><b>绝对路径不进出本端口。</b>适配器负责把相对路径归一化并挡住越出根目录的输入；
 * 对外暴露的访问地址一律是受控下载路由或短时效签名 URL，不是文件系统路径。</p>
 *
 * <p><b>为什么用 {@code byte[]} 而不是流</b>：本项目的文件只有底图（上限 20MB，D09-⑥）
 * 与复盘导出件，单机规模的并发极低；整块读写省掉了调用方管理流生命周期的负担。
 * 若日后接入大文件（录像等），再引入流式方法，而不是现在就把复杂度铺开。</p>
 */
public interface FileStore {

    /**
     * 写入文件，已存在则整体替换（D09-②：换图即覆盖，旧内容不保留）。
     *
     * <p>目录不存在时由适配器创建。实现必须避免「写到一半被读到」——先写临时文件再改名。</p>
     */
    void put(String relativePath, byte[] content);

    /** 读取文件；不存在时返回空，不抛异常（调用方要区分 404 与 500）。 */
    Optional<byte[]> read(String relativePath);

    /**
     * 删除文件。
     *
     * @return 确实删掉了返回 {@code true}；文件本就不存在返回 {@code false}（幂等，由调用方决定是否算错）
     */
    boolean delete(String relativePath);

    /** 文件是否存在。 */
    boolean exists(String relativePath);
}
