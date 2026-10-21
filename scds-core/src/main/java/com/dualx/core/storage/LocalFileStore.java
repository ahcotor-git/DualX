package com.dualx.core.storage;

import com.dualx.core.port.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FileStore} 的本地文件系统实现（D09-①）。
 *
 * <p><b>越界防护是本类的核心职责。</b>{@code image_path} 与上传文件名最终都可能来自请求，
 * 若直接 {@code root.resolve(用户输入)}，一个 {@code ../../} 就能写到存储目录之外——
 * 单机部署下那是整台机器的文件系统。故所有路径先归一化再校验仍落在根目录内，
 * 越界即拒绝，不做「尽力清洗后照写」。</p>
 *
 * <p><b>原子替换</b>：写临时文件再改名，避免读侧读到半截图片。改名不跨目录，
 * 故同一文件系统内是可移植的替换语义（Windows/POSIX 都是替换型改名）。</p>
 *
 * <p><b>启动即校验根目录</b>：目录在构造期创建并检查可写。现场部署时目录写错
 * 应该在启动日志里立刻看到，而不是等到第一次上传图片才失败。</p>
 */
@Component
@EnableConfigurationProperties(FileStorageProperties.class)
public class LocalFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStore.class);

    private final Path root;

    public LocalFileStore(FileStorageProperties properties) {
        this.root = Paths.get(properties.root()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("文件存储根目录创建失败：" + root, e);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("文件存储根目录不可写：" + root);
        }
        log.info("文件存储根目录就绪：{}", root);
    }

    @Override
    public void put(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
            Path temp = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败：" + relativePath, e);
        }
    }

    @Override
    public Optional<byte[]> read(String relativePath) {
        Path target = resolve(relativePath);
        try {
            if (!Files.exists(target)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + relativePath, e);
        }
    }

    @Override
    public boolean delete(String relativePath) {
        try {
            return Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("删除文件失败：" + relativePath, e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /**
     * 把相对路径解析成根目录内的绝对路径。
     *
     * <p>把反斜杠也当分隔符统一处理：现场用 Windows 部署，调用方或配置里混进
     * {@code maps\a.png} 时，若不归一化就会在 Windows 上解析成「文件名带反斜杠」的怪路径。</p>
     *
     * @throws IllegalArgumentException 路径为空，或归一化后越出根目录（含传入绝对路径的情况）
     */
    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件相对路径不能为空");
        }
        Path resolved = root.resolve(relativePath.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("文件相对路径越出存储根目录：" + relativePath);
        }
        return resolved;
    }
}
