package com.atifah.gateway.support;

import com.atifah.gateway.config.GatewayClientIpProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 真实客户端 IP 解析：仅当 TCP 对端命中受信网段时才信任转发头。
 * 只接受 IP 字面量，禁止 DNS 解析；受信网段非法时启动失败。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Component
public class ClientIpResolver {

    /** 标准转发头。 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** Nginx 单值转发头。 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 转发头最大长度与最大跳数，避免超长头消耗 CPU。 */
    private static final int MAX_HEADER_LENGTH = 1024;
    private static final int MAX_CHAIN_SIZE = 20;

    /** IPv4 点分十进制；取值范围交给 {@link InetAddress} 校验。 */
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    /** IPv6 字符集；含冒号即不可能是主机名，避免触发 DNS。 */
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<IpRange> trustedRanges;

    public ClientIpResolver(GatewayClientIpProperties properties) {
        this.trustedRanges = properties.getTrustedProxies().stream()
                .map(IpRange::parse)
                .toList();
    }

    /**
     * 解析客户端 IP。
     *
     * @param request 请求
     * @return 客户端 IP，无法解析时返回 {@code null}
     */
    public String resolve(ServerHttpRequest request) {
        InetAddress remote = remoteAddress(request);
        if (remote == null) {
            return null;
        }
        if (!isTrusted(remote)) {
            return remote.getHostAddress();
        }
        List<String> chain = forwardedChain(request);
        if (chain.isEmpty()) {
            return remote.getHostAddress();
        }
        InetAddress leftmost = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress candidate = parseLiteral(chain.get(i));
            if (candidate == null) {
                continue;
            }
            leftmost = candidate;
            if (!isTrusted(candidate)) {
                return candidate.getHostAddress();
            }
        }
        return leftmost == null ? remote.getHostAddress() : leftmost.getHostAddress();
    }

    /**
     * 取 TCP 对端地址。
     *
     * @param request 请求
     * @return 对端地址，缺失时返回 {@code null}
     */
    private InetAddress remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? null : remote.getAddress();
    }

    /**
     * 读取转发链：优先 X-Forwarded-For，其次 X-Real-IP。
     *
     * @param request 请求
     * @return 从右向左的候选链（右为最靠近网关的代理），无有效头时为空列表
     */
    private List<String> forwardedChain(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst(HEADER_X_FORWARDED_FOR);
        if (StringUtils.hasText(forwarded)) {
            return split(forwarded);
        }
        String realIp = request.getHeaders().getFirst(HEADER_X_REAL_IP);
        return StringUtils.hasText(realIp) ? List.of(realIp.trim()) : List.of();
    }

    /**
     * 拆分并裁剪转发链。
     *
     * @param header 转发头原值
     * @return 候选地址列表
     */
    private List<String> split(String header) {
        String value = header.length() > MAX_HEADER_LENGTH
                ? header.substring(0, MAX_HEADER_LENGTH)
                : header;
        String[] parts = value.split(",");
        List<String> chain = new ArrayList<>(Math.min(parts.length, MAX_CHAIN_SIZE));
        for (String part : parts) {
            if (chain.size() >= MAX_CHAIN_SIZE) {
                break;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                chain.add(trimmed);
            }
        }
        return chain;
    }

    /**
     * 判断地址是否命中任一受信网段。
     *
     * @param address 地址
     * @return 是否受信
     */
    private boolean isTrusted(InetAddress address) {
        return trustedRanges.stream().anyMatch(range -> range.contains(address));
    }

    /**
     * 按 IP 字面量解析；非法或非字面量返回 {@code null}，不触发 DNS。
     *
     * @param value 待解析值
     * @return 地址或 {@code null}
     */
    static InetAddress parseLiteral(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        boolean ipv4 = IPV4.matcher(text).matches();
        boolean ipv6 = text.indexOf(':') >= 0 && IPV6_CHARS.matcher(text).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    /**
     * 受信网段。
     *
     * @param network 网络号
     * @param prefix 前缀长度
     * @param bits 地址总位数
     */
    private record IpRange(BigInteger network, int prefix, int bits) {

        /**
         * 解析 CIDR 或单 IP；非法配置直接抛出，由启动阶段 fail-fast。
         *
         * @param raw 配置值
         * @return 网段
         */
        static IpRange parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                throw new IllegalArgumentException("受信代理网段不能为空");
            }
            String value = raw.trim();
            int slash = value.indexOf('/');
            String addressPart = slash >= 0 ? value.substring(0, slash) : value;
            InetAddress address = parseLiteral(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("受信代理网段不是合法 IP: " + raw);
            }
            int bits = address.getAddress().length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("受信代理网段前缀非法: " + raw, ex);
                }
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("受信代理网段前缀越界: " + raw);
            }
            return new IpRange(new BigInteger(1, address.getAddress()), prefix, bits);
        }

        /**
         * 判断地址是否落在本网段内；IPv4 与 IPv6 互不匹配。
         *
         * @param address 地址
         * @return 是否命中
         */
        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length * 8 != bits) {
                return false;
            }
            if (prefix == 0) {
                return true;
            }
            int shift = bits - prefix;
            return new BigInteger(1, bytes).shiftRight(shift).equals(network.shiftRight(shift));
        }
    }
}
