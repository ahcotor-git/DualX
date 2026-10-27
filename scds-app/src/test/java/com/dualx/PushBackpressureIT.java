package com.dualx;

import com.dualx.api.ws.SituationSessionRegistry;
import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.state.SituationQuery;
import com.dualx.core.support.UuidV7;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import com.dualx.protocol.Team;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 背压隔离的端到端验收，即设计 [02-push-ports](../../docs/design/02-push-ports.md) §7 的<b>判据 4</b>。
 *
 * <p><b>判据原文</b>：「人为让一个客户端不读取数据（填满其 TCP 缓冲）→ 其他会话的推送延迟不受影响；
 * 该客户端恢复后收到一帧 {@code full=true} 并自动对齐（丢弃计数 &gt; 0，其余会话的计数为 0）。」</p>
 *
 * <p><b>为什么必须端到端、且必须用真客户端</b>：这条判据的两半都只在真实 socket 上才成立——
 * 前半句要求「写不动」的效果真的沿 TCP 传回服务端的发送线程（否则会话队列永远不会满、丢帧计数
 * 永远是 0），后半句要求丢帧之后由发送线程自己改推一帧全量把客户端对齐。会话表单测（
 * {@code SituationSessionRegistryTest}）只能证明「队满不阻塞调用方」，证明不了这两件事合起来的效果。</p>
 *
 * <p><b>「不读取数据」怎么造</b>：用裸 socket 走一次真的 WS 握手（HTTP Upgrade + 契约要求的
 * {@code Authorization}），读完连接建立时的首帧后<b>就此不再读</b>。裸 socket 的意义在于「不读」是
 * 结构性的：不调用 {@code read}，就没有任何库能在背后替它读——而现成的 WS 客户端库都会自带读取线程与
 * 内部缓冲，判据要的那种「读不动」在它们身上只能靠契约以外的行为去凑。接收缓冲另在 {@code connect}
 * 之前压小（{@link #SLOW_CLIENT_RCVBUF}），使宿主的 TCP 缓冲尽快被填满。</p>
 *
 * <p><b>「其余会话的计数为 0」怎么取证</b>：{@code droppedFrameCount()} 与 {@code resyncCount()} 是
 * <b>全进程</b>计数（设计 02 §4.7 的三个观测），没有按会话维度，所以判据里的「其余会话计数为 0」不能
 * 靠读计数直接判定。本类改用<b>等价且更强的现场证据</b>：健康会话（普通 WS 客户端）自己的帧流就是它的
 * 计数——它每收一帧都核对 revision 是否恰好多 1，只要它全程未出现跳号、且订阅之后再没收到过
 * {@code full=true}，就证明了它既没被丢帧（dropped＝0）也没被改推全量（resync＝0）。</p>
 *
 * <p>不写成 {@code @Transactional}：慢客户端的停滞与恢复都是进程内运行态，不落库；库里的行由
 * {@link App#closeAndClean()} 按本用例自造的场次/想定精确回收。</p>
 */
class PushBackpressureIT {

    // ── 协议黄金样本（与 SituationStateIT 同源，未复算） ─────────────────────────

    /** {@code ff03} 短版心跳：马甲 2 / 弹药 30/30 / HP 100 → 离线转 LIVE。 */
    private static final String HEARTBEAT_VEST2_LIVE = "ff03000002001e64001ea4affa";

    /** {@code ff04} 室外 GPS：马甲 2 → 定位变化。重复投喂每次稳定推高一位 revision。 */
    private static final String GPS_VEST2 = "ff0400000207136d12017e8822c7affa";

    /**
     * 每会话待发帧上限（覆写 {@code scds.push.send-queue-capacity}）。
     *
     * <p>压到 4 只是让「队满」这一步更快到达，不改判据：丢帧与收敛全量的逻辑与容量无关。
     * 真正让它必然发生的是慢客户端侧的 TCP 缓冲被填满。</p>
     */
    private static final int SEND_QUEUE_CAPACITY = 4;

    /**
     * 空闲断开阈值覆写（契约值 30000）。慢客户端全程不发上行，本类又要在它停滞时注入较多帧；
     * 调大只为让「慢」与「空闲」两件事互不干扰——30s 的真实阈值由 {@code PushKeepAliveIT} 与现场验收覆盖。
     */
    private static final long IDLE_TIMEOUT_MS = 120_000L;

    /** 慢客户端的接收缓冲：必须在 connect 之前设小，否则要灌上百帧才轮得到服务端写阻塞。 */
    private static final int SLOW_CLIENT_RCVBUF = 4096;

    /**
     * 编成条数。刻意多于「判据所需」：信封里每多一条单元，一帧就大一点，
     * 服务端的写因此更快被慢客户端的 TCP 缓冲堵住——判据 4 的场景更快到达，与语义无关。
     */
    private static final int ROSTER_SIZE = 24;

    /** 停滞阶段注入上限（防呆）：正常十几到几十帧就该出现丢帧。 */
    private static final int STALL_INJECTION_LIMIT = 400;

    /** 恢复阶段继续注入的帧数：给慢会话腾出「吐掉陈旧前缀 → 撞上缺口 → 收敛全量 → 逐帧续上」的机会。 */
    private static final int RECOVERY_INJECTIONS = 40;

    private static final List<RosterRow> ROSTER = rosterOf(ROSTER_SIZE);

    private static final String PORT_NAME = "COM7";

    private static KeyPair keyPair;
    private static String admin;

    @BeforeAll
    static void keyAndToken() throws Exception {
        keyPair = ApplicationStartupSupport.newKeyPair();
        admin = ApplicationStartupSupport.externalAccessToken(keyPair, List.of("ADMIN"));
    }

    @Test
    @DisplayName("背压隔离（判据 4）：慢客户端只堵它自己——其余会话的帧流零跳号；它恢复后被收敛为一帧全量并自动对齐")
    void slowClientIsIsolatedAndConvergesToFullAfterRecovery() throws Exception {
        App app = App.boot(keyPair);
        try {
            app.openSerialLink();
            app.startExercise();
            app.injectAndAwaitBump(HEARTBEAT_VEST2_LIVE);

            // 健康会话：普通 WS 客户端，边收边核对（它的帧流本身就是「其余会话的计数」）。
            HealthyCollector healthy = new HealthyCollector(app.mapper);
            try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/situation"), admin, healthy)) {
                JsonNode first = healthy.awaitEnvelope(Duration.ofSeconds(5));
                assertThat(first.path("full").asBoolean()).as("不带 sinceRevision → 全量起步").isTrue();
                assertThat(first.path("units")).hasSize(ROSTER.size());

                // 慢会话：裸 socket 握手成功、读掉首帧后就此不再读——它的 TCP 缓冲会先被填满。
                SlowClient slow = SlowClient.open("127.0.0.1", app.port, admin, app.mapper);
                try {
                    long slowBaseline = slow.initialRevision();

                    // ── 阶段一：慢客户端停止读取 → 服务端先填满它的 TCP 缓冲与队列 → 丢帧真的发生 ──
                    int injected = 0;
                    while (app.sessions.droppedFrameCount() == 0 && injected < STALL_INJECTION_LIMIT) {
                        injectAndAwaitHealthy(app, healthy);
                        injected++;
                    }
                    assertThat(app.sessions.droppedFrameCount())
                            .as("慢客户端一直不读取，服务端必须先填满它的 TCP 缓冲与队列、然后丢帧（注入 %d 帧后仍为 0）",
                                    injected)
                            .isGreaterThan(0);
                    assertThat(healthy.lastRevision())
                            .as("慢客户端停滞期间，其他会话的推送必须照常推进（它若也被拖住，本断言先失败）")
                            .isGreaterThan(slowBaseline);

                    // ── 阶段二：慢客户端恢复读取 → 吐掉陈旧前缀后必须被收敛为一帧 full=true ──
                    slow.startDraining();
                    for (int i = 0; i < RECOVERY_INJECTIONS && slow.fullFrameCount() == 0; i++) {
                        injectAndAwaitHealthy(app, healthy);
                    }
                    await("慢客户端恢复后必须收到一帧 full=true（丢帧被收敛为全量）",
                            () -> slow.fullFrameCount() > 0);
                    assertThat(slow.lastFullRevision())
                            .as("这帧是丢帧之后重新对齐用的全量，不是连接时收到、还留在缓冲里的那一帧")
                            .isGreaterThan(slowBaseline);

                    // ── 阶段三：停注入并等它与服务端对齐；此后不得再出现缺口 ──
                    await("慢客户端必须追平服务端 revision",
                            () -> slow.lastRevision() >= app.query.revision());
                    assertThat(slow.lastRevision()).isEqualTo(app.query.revision());
                    assertThat(slow.gapCount())
                            .as("full 帧之后逐帧续上：客户端凭它的 revision 直接对齐，不再需要任何补齐")
                            .isZero();

                    // ── 「其余会话的计数为 0」的等价取证：健康会话的帧流全程零跳号、零补齐 ──
                    assertThat(healthy.gapCount()).as("健康会话从未出现 revision 跳号（即从未被丢帧）").isZero();
                    assertThat(healthy.fullFrameCount())
                            .as("健康会话只在订阅时收到过一帧全量（此后从未被改推全量）")
                            .isEqualTo(1);
                    assertThat(healthy.lastRevision()).as("健康会话已追平服务端").isEqualTo(app.query.revision());
                    assertThat(app.sessions.resyncCount())
                            .as("收敛全量是慢会话造成的（全局计数，无按会话维度）")
                            .isGreaterThan(0);
                } finally {
                    slow.close();
                }
            }
        } finally {
            app.closeAndClean();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /**
     * 注入一帧并等两件事都成立：写手线程把它变成一次 revision 递增；健康会话已经收到该 revision。
     *
     * <p>第二件事是本节拍的<u>节流阀</u>：不节流的话，健康会话自己的待发队列也会被灌满、跟着丢帧，
     * 「慢会话与健康会话」就无法区分，判据 4 也就无从取证。</p>
     */
    private static void injectAndAwaitHealthy(App app, HealthyCollector healthy) {
        long before = app.query.revision();
        app.links.injectSerial(GPS_VEST2);
        await("写手线程把注入帧变成一次 revision 递增", () -> app.query.revision() > before);
        await("健康会话必须逐帧跟上，不得堆积（否则它也会丢帧，判据 4 的隔离就无从证明）",
                () -> healthy.lastRevision() >= app.query.revision());
    }

    /** 轮询到条件成立或超时：写手线程、发送线程与网络都是异步的，断言前必须等。 */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        throw new AssertionError("等待超时（20s）：" + what);
    }

    private static List<RosterRow> rosterOf(int size) {
        List<RosterRow> rows = new ArrayList<>(size);
        for (int vestNum = 1; vestNum <= size; vestNum++) {
            rows.add(new RosterRow(vestNum, "马甲-" + vestNum, vestNum % 2 == 0 ? Team.RED : Team.BLUE));
        }
        return List.copyOf(rows);
    }

    /** 编成里的一行（只用于铺库，真值仍以库读回的 RosterEntry 为准）。 */
    private record RosterRow(int vestNum, String name, Team team) {
    }

    // ── 健康会话：普通 WS 客户端，边收边核对 ──────────────────────────────────

    /**
     * 只收不判的普通客户端，外加一条就地核对：每帧 revision 是否恰好多 1。
     *
     * <p>这条核对之所以能替判据里「其余会话的计数为 0」，是因为服务端的两个计数恰好对应这两种现象：
     * 丢帧会让 revision 跳号（或直接表现为后来的 {@code full=true}），改推全量会表现为非首帧的
     * {@code full=true}。于是「跳号数＝0 且 full 帧数＝1」就等于该会话的 dropped／resync 都是 0。</p>
     */
    private static final class HealthyCollector extends TextWebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;

        private volatile long lastRevision = -1;
        private volatile long fullFrameCount;
        private volatile long gapCount;

        HealthyCollector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            String payload = message.getPayload();
            frames.add(payload);
            try {
                JsonNode node = mapper.readTree(payload);
                if (!node.has("revision")) {
                    return; // 控制帧（PONG）不是信封，不参与连续性
                }
                long revision = node.path("revision").asLong();
                if (node.path("full").asBoolean()) {
                    fullFrameCount++;
                } else if (lastRevision >= 0 && revision != lastRevision + 1) {
                    gapCount++;
                }
                lastRevision = revision;
            } catch (Exception e) {
                throw new IllegalStateException("健康会话收到无法解析的帧", e);
            }
        }

        long lastRevision() {
            return lastRevision;
        }

        long fullFrameCount() {
            return fullFrameCount;
        }

        long gapCount() {
            return gapCount;
        }

        JsonNode awaitEnvelope(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                String payload = frames.poll(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
                if (payload == null) {
                    break;
                }
                JsonNode node = mapper.readTree(payload);
                if (node.has("revision")) {
                    return node;
                }
            }
            throw new AssertionError("等待态势信封超时（已收 " + frames.size() + " 帧）");
        }
    }

    /** 一次 WS 会话；仅用于在用例结束时把连接关干净，不给下一次用例留残留会话。 */
    private static final class Ws implements AutoCloseable {

        private final WebSocketSession session;

        private Ws(WebSocketSession session) {
            this.session = session;
        }

        static Ws open(String url, String token, TextWebSocketHandler handler) throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            WebSocketSession session = new StandardWebSocketClient()
                    .execute(handler, headers, URI.create(url))
                    .get(5, TimeUnit.SECONDS);
            return new Ws(session);
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(org.springframework.web.socket.CloseStatus.NORMAL);
            }
        }
    }

    // ── 慢会话：裸 socket，读完首帧就「读不动」 ────────────────────────────────

    /**
     * 判据 4 里那个「不读取数据」的客户端：自己走完 WS 握手，然后<b>不再读 socket</b>。
     *
     * <p>不使用现成 WS 客户端库是有意的：它们的读取线程与内部缓冲会把「客户端读不动」这件事的时机
     * 藏起来，判据要的是确定性的「服务端写阻塞」。裸 socket 下「不读」就是字面意思——不调用
     * {@code read}，TCP 接收窗口随之关闭，服务端的 {@code sendMessage} 必然阻塞。</p>
     *
     * <p>{@link #startDraining()} 之后由一条虚拟线程按帧读回，并就地记下 revision 的连续性——
     * 恢复后「先吐陈旧前缀、再收一帧全量、然后逐帧续上」这三步都由 {@link #gapCount()} 收口。</p>
     */
    private static final class SlowClient implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ObjectMapper mapper;
        private final Thread drainer;

        private volatile long initialRevision = -1;
        private volatile long lastRevision = -1;
        private volatile long lastFullRevision = -1;
        private volatile long fullFrameCount;
        private volatile long gapCount;
        private volatile boolean draining;

        private SlowClient(Socket socket, ObjectMapper mapper) throws IOException {
            this.socket = socket;
            this.mapper = mapper;
            this.in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            this.out = new BufferedOutputStream(socket.getOutputStream());
            this.drainer = Thread.ofVirtual().name("slow-ws-client").unstarted(this::drain);
        }

        /**
         * 连上并完成握手，然后<b>只读一帧</b>（连接建立时的全量帧）就停手。
         *
         * <p>读掉这一帧是为了让后面收到的 {@code full=true} 不含糊：它只能是丢帧之后收敛来的那一帧，
         * 而不是还压在接收缓冲里没人取的连接首帧。</p>
         */
        static SlowClient open(String host, int port, String token, ObjectMapper mapper) throws Exception {
            Socket socket = new Socket();
            // 必须在 connect 之前设：这正是「尽快把服务端写入堵住」的第一步。
            socket.setReceiveBufferSize(SLOW_CLIENT_RCVBUF);
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            SlowClient client = new SlowClient(socket, mapper);
            client.handshake(host, port, token);
            JsonNode first = mapper.readTree(client.readMessage());
            client.initialRevision = first.path("revision").asLong();
            client.lastRevision = client.initialRevision;
            return client;
        }

        /** 恢复读取：从这里开始，服务端的发送线程才有机会写下去。 */
        void startDraining() {
            draining = true;
            drainer.start();
        }

        long initialRevision() {
            return initialRevision;
        }

        long lastRevision() {
            return lastRevision;
        }

        long lastFullRevision() {
            return lastFullRevision;
        }

        long fullFrameCount() {
            return fullFrameCount;
        }

        /** 最后一帧 {@code full=true} 之后的跳号次数；客户端被对齐之后必须恒为 0。 */
        long gapCount() {
            return gapCount;
        }

        private void drain() {
            while (draining) {
                try {
                    record(mapper.readTree(readMessage()));
                } catch (IOException e) {
                    return; // 连接被关掉就结束，没有继续读的意义
                } catch (Exception e) {
                    throw new IllegalStateException("慢客户端读取失败", e);
                }
            }
        }

        private void record(JsonNode node) {
            long revision = node.path("revision").asLong();
            if (node.path("full").asBoolean()) {
                fullFrameCount++;
                lastFullRevision = revision;
                // 全量帧把客户端对齐了：缺口计数从这一帧重新开始数。
                gapCount = 0;
            } else if (lastRevision >= 0 && revision != lastRevision + 1) {
                gapCount++;
            }
            lastRevision = revision;
        }

        private void handshake(String host, int port, String token) throws IOException {
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String request = "GET /ws/v1/situation HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    // WS 握手走同一条安全链：没有账号令牌连不上升级（设计 03 §4.7）。
                    + "Authorization: Bearer " + token + "\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String head = readHttpHead();
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new IllegalStateException("态势 WS 升级失败（判据 4 的慢客户端必须真的握手成功）："
                        + head.lines().findFirst().orElse(head));
            }
        }

        private String readHttpHead() throws IOException {
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("WS 握手响应未读完连接就断了");
                }
                head.append((char) b);
                if (head.length() > 8192) {
                    throw new IllegalStateException("WS 握手响应头异常大");
                }
            }
            return head.toString();
        }

        /**
         * 读一条完整的文本消息。
         *
         * <p>只实现读回来必需的部分：服务器→客户端的帧不带掩码（RFC 6455），只关心 opcode
         * 0x1（文本）/0x0（续帧）与 0x8（关闭）；0x9 心跳等控制帧读完即跳过。</p>
         */
        private String readMessage() throws IOException {
            StringBuilder text = new StringBuilder();
            while (true) {
                int first = in.read();
                if (first < 0) {
                    throw new EOFException("服务端已关闭连接");
                }
                int opcode = first & 0x0F;
                boolean fin = (first & 0x80) != 0;

                int second = in.read();
                if (second < 0) {
                    throw new EOFException("服务端已关闭连接");
                }
                if ((second & 0x80) != 0) {
                    throw new IOException("服务端→客户端的帧不应带掩码");
                }
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((long) readByte() << 8) | readByte();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | readByte();
                    }
                }
                byte[] payload = new byte[Math.toIntExact(length)];
                readFully(payload);

                if (opcode == 0x8) {
                    throw new EOFException("服务端发来关闭帧");
                }
                if (opcode == 0x1 || opcode == 0x0) {
                    text.append(new String(payload, StandardCharsets.UTF_8));
                }
                if (fin && (opcode == 0x1 || opcode == 0x0)) {
                    return text.toString();
                }
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("服务端已关闭连接");
            }
            return b;
        }

        private void readFully(byte[] buffer) throws IOException {
            int filled = 0;
            while (filled < buffer.length) {
                int read = in.read(buffer, filled, buffer.length - filled);
                if (read < 0) {
                    throw new EOFException("服务端已关闭连接");
                }
                filled += read;
            }
        }

        @Override
        public void close() {
            draining = false;
            drainer.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
                // 收尾用的关闭失败不改变任何结论。
            }
        }
    }

    /**
     * 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP/WS，以及仿真网关。
     *
     * <p>容量与空闲阈值由本类的环境覆写（见 {@link #boot}）——判据 4 要的是「队满 + TCP 堵死」这一
     * 组现象，覆写只把到达该现象所需的帧数压到用例能承受的量级，不改任何分支语义。</p>
     */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String base;
        private final String authority;
        private final int port;
        private final HttpClient http;
        private final SimulatedLinkAdapter links;
        private final JdbcClient jdbc;
        private final SituationQuery query;
        private final SituationSessionRegistry sessions;
        private final UUID planId = UuidV7.generate();
        private String exerciseId;

        private App(ConfigurableEnvironment environment) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            this.port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.authority = "127.0.0.1:" + port;
            this.base = "http://" + authority;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            this.links = (SimulatedLinkAdapter) context.getBean(DeviceLinkAdmin.class);
            this.jdbc = context.getBean(JdbcClient.class);
            this.query = context.getBean(SituationQuery.class);
            this.sessions = context.getBean(SituationSessionRegistry.class);
        }

        private static App boot(KeyPair key) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            environment.getPropertySources().addFirst(new MapPropertySource("push-backpressure-it", Map.of(
                    "scds.gateway.mode", "simulator",
                    "scds.push.send-queue-capacity", Integer.toString(SEND_QUEUE_CAPACITY),
                    "scds.push.idle-timeout-ms", Long.toString(IDLE_TIMEOUT_MS))));
            return new App(environment);
        }

        /** 投喂上行帧要求链路已开（仿真下不开链路投喂是非法用法）。 */
        private void openSerialLink() {
            links.openSerial(new LinkConfig(LinkKind.SERIAL_VEST, PORT_NAME, null,
                    false, null, null, false, null, null, null));
        }

        /** 投喂一帧并等写手线程把它变成一次 revision 递增。 */
        private void injectAndAwaitBump(String frame) {
            long before = query.revision();
            links.injectSerial(frame);
            await("投喂一帧必须推高一位 revision", () -> query.revision() > before);
        }

        /** 走真 HTTP 开演（真 LIFE → 真 binder → 真状态机），而不是直接调 binder。 */
        private void startExercise() throws Exception {
            // 想定与编成必须先落库：开演入口是从 t_plan / t_plan_roster 读编成的。
            seedPlanAndRoster();
            HttpResponse<String> response = post("/api/v1/exercise/current",
                    mapper.createObjectNode().put("planId", planId.toString()), admin);
            assertThat(response.statusCode()).isEqualTo(201);
            exerciseId = mapper.readTree(response.body()).path("exerciseId").asText();
        }

        private void seedPlanAndRoster() {
            jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                            + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                    .param("id", planId.toString())
                    .param("name", "背压隔离IT想定-" + planId).update();
            for (RosterRow row : ROSTER) {
                jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                                + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                        .param("planId", planId.toString())
                        .param("vestNum", row.vestNum()).param("name", row.name())
                        .param("team", row.team().name()).update();
            }
        }

        private String wsUrl(String path) {
            return "ws://" + authority + path;
        }

        private HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearer);
        }

        private HttpResponse<String> send(HttpRequest.Builder request, String bearer) throws Exception {
            if (bearer != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        /** 收尾：按场次与想定精确回收本用例写下的行（无外键，顺序无关）。 */
        private void closeAndClean() {
            try {
                if (exerciseId != null) {
                    for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                            "t_exercise_command", "t_exercise_log")) {
                        jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                                .param("id", exerciseId).update();
                    }
                    jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                        .param("planId", planId.toString()).update();
                jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                        .param("planId", planId.toString()).update();
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
