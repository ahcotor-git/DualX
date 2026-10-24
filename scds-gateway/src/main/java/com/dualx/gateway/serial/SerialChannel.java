package com.dualx.gateway.serial;

import java.io.IOException;

/**
 * 串口通道边界：把 jSerialComm 关在唯一实现（{@link JSerialChannel}）里。
 *
 * <p>存在的理由有两条：一是无硬件时可以用假通道验证成帧、轮询与发送队列，
 * 不必把串口库 mock 掉；二是换串口库、或适配现场驱动差异时只影响一个实现类。</p>
 *
 * <p>方法语义刻意贴近字节流本身，不含任何协议知识——成帧与业务在链路层与核心侧。</p>
 */
public interface SerialChannel {

    /**
     * 打开串口。
     *
     * @throws IOException 端口不存在、被占用或参数不被接受
     */
    void open() throws IOException;

    boolean isOpen();

    /**
     * 半阻塞读一批字节。
     *
     * @param buffer 目标缓冲
     * @return 本次读到的字节数；0 表示读超时无数据
     * @throws IOException 端口已断开或读失败
     */
    int read(byte[] buffer) throws IOException;

    /**
     * 写出一整帧。
     *
     * @throws IOException 未全部写出（写超时或端口已断开）
     */
    void write(byte[] data) throws IOException;

    /** 关闭串口；可重复调用。 */
    void close();

    /** 用于 {@code LinkStatus.target} 的描述，如 {@code COM7}。 */
    String target();
}
