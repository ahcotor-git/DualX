package com.dualx.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地文件存储的边界行为（D09-①）。用 JUnit 的 {@code @TempDir} 真读写临时目录——
 * 越界拒绝与原子替换都只在真实文件系统上才验得准。
 */
class LocalFileStoreTest {

    private static final String SECRET = "s".repeat(48);

    @TempDir
    Path tempDir;

    private Path root;
    private LocalFileStore store;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("files");
        store = new LocalFileStore(new FileStorageProperties(root.toString(), SECRET, null, null));
    }

    @Test
    void constructorCreatesRootDirectory() {
        assertThat(root).isDirectory();
    }

    @Test
    void putThenReadRoundTrip() {
        byte[] content = "底图字节".getBytes(StandardCharsets.UTF_8);

        store.put("maps/0f8f/map.png", content);

        assertThat(store.exists("maps/0f8f/map.png")).isTrue();
        assertThat(store.read("maps/0f8f/map.png")).hasValueSatisfying(
                actual -> assertThat(actual).isEqualTo(content));
    }

    @Test
    void readMissingFileReturnsEmptyInsteadOfFailing() {
        assertThat(store.read("maps/absent/map.png")).isEmpty();
        assertThat(store.exists("maps/absent/map.png")).isFalse();
    }

    @Test
    void putReplacesExistingContentAndLeavesNoTempFile() throws IOException {
        store.put("maps/map.png", "第一版".getBytes(StandardCharsets.UTF_8));
        store.put("maps/map.png", "第二版".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read("maps/map.png")).hasValueSatisfying(
                actual -> assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo("第二版"));
        // 替换走「写临时文件再改名」，改名后不该在目录里留下 .tmp-* 残骸
        try (Stream<Path> entries = Files.list(root.resolve("maps"))) {
            assertThat(entries.map(p -> p.getFileName().toString()).toList())
                    .containsExactly("map.png");
        }
    }

    @Test
    void deleteIsIdempotent() {
        store.put("maps/map.png", new byte[]{1});

        assertThat(store.delete("maps/map.png")).isTrue();
        assertThat(store.exists("maps/map.png")).isFalse();
        assertThat(store.delete("maps/map.png")).isFalse();
    }

    @Test
    void windowsSeparatorsAreNormalizedToTheSameFile() {
        store.put("maps\\sub\\map.png", new byte[]{7});

        assertThat(store.exists("maps/sub/map.png")).isTrue();
        assertThat(store.read("maps/sub/map.png")).isPresent();
    }

    @Test
    void blankRelativePathIsRejected() {
        assertThatThrownBy(() -> store.put(null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("   ", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathPointingAtTheRootItselfIsRejected() {
        assertThatThrownBy(() -> store.put(".", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absolutePathIsRejected() {
        assertThatThrownBy(() -> store.put("/etc/passwd", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parentTraversalIsRejectedEvenWhenItEscapesOnlyAfterNormalization() {
        assertThatThrownBy(() -> store.put("../evil.png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("maps/../../evil.png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        // 越界校验必须发生在建目录之前：被拒绝的路径不该在根目录外留下任何痕迹
        assertThat(tempDir.toFile().listFiles())
                .extracting(File::getName)
                .containsExactly("files");
    }

    @Test
    void dotDotStayingInsideRootIsAllowed() {
        store.put("maps/map.png", new byte[]{3});

        assertThat(store.read("maps/../maps/map.png")).isPresent();
    }

    @Test
    void missingDirectoriesAreCreatedOnPut() {
        store.put("a/b/c/d.png", new byte[]{1});

        assertThat(root.resolve("a/b/c/d.png")).exists();
    }
}
