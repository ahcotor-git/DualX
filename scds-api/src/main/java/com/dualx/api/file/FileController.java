package com.dualx.api.file;

import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.FileStore;
import com.dualx.core.port.FileUrlSigner;
import com.dualx.core.storage.ImageHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受保护文件的签名 URL 读取（契约 docs/api/paths/files.yaml；D09-②）。
 *
 * <p><b>为什么是独立路由而不是挂在各域下</b>：受保护文件不止底图（还有复盘导出件），
 * 按域各开一条「免 Bearer 的下载路由」意味着每接一类文件就要多一条白名单条目，
 * 而白名单每多一条就多一处漏收紧的机会。签名本身已经是授权凭据，路由不必再分域。</p>
 *
 * <p><b>为什么免 Bearer</b>：这个 URL 是给 {@code <img>} 与浏览器直接加载的，
 * 它们不会为图片请求带上 {@code Authorization}（契约 {@code MapDetail.imageUrl}）。
 * 放行只发生在安全链这一层，**验签在本类**——放行不等于放行访问。</p>
 *
 * <p><b>两道防线</b>：签名（只有服务端能生成）＋ {@link FileStore} 适配器对越界路径的拒绝。
 * 后者不因前者而省略：签名密钥一旦泄露或被配弱，{@code path=../../etc/passwd} 就是现成的
 * 任意文件读取，而路径归一化是存储层独立于令牌体系的第二道闸。</p>
 */
@RestController
public class FileController {

    private final FileStore store;
    private final FileUrlSigner signer;

    public FileController(FileStore store, FileUrlSigner signer) {
        this.store = store;
        this.signer = signer;
    }

    /**
     * 按签名 URL 读取文件。
     *
     * <p>三个查询参数由签发方给出（底图详情里的 {@code imageUrl} 就是同一组值），
     * 客户端只该原样转发：签名覆盖 {@code path} 与 {@code exp}，改动任一个都验不过。</p>
     *
     * <p>签名不成立与已过期折叠成同一个 403 {@link ApiErrorCode#FILE_URL_INVALID}
     * （D09-②）：客户端处置动作相同（回详情接口取新 URL），而区分两者只会告诉探测者
     * 「这个路径确实存在，只是过期了」。</p>
     */
    @GetMapping("/api/v1/files")
    public ResponseEntity<byte[]> getFile(@RequestParam String path,
                                          @RequestParam String exp,
                                          @RequestParam String signature) {
        if (!signer.verify(path, exp, signature)) {
            throw new BusinessException(ApiErrorCode.FILE_URL_INVALID, "文件访问签名无效或已过期");
        }
        byte[] content = store.read(path)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MAP_IMAGE_MISSING, "文件不存在：" + path));
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(content))
                .body(content);
    }

    /**
     * 按真实字节判定 {@code Content-Type}，判不出来给 {@code application/octet-stream}。
     *
     * <p>不靠扩展名：文件名由上传方参与构成，而这里回的是给浏览器直接渲染的内容。
     * 非图片的受保护文件（如复盘导出件）走的正是 octet-stream 那条分支，与契约列的
     * 四种 200 内容类型一一对应（D09-④：按原格式返回，不统一转码）。</p>
     */
    private static MediaType mediaTypeOf(byte[] content) {
        return ImageHeaders.sniff(content)
                .map(size -> MediaType.parseMediaType(size.format().contentType()))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
