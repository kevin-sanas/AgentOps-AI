package dev.agentops.targets;

import dev.agentops.shared.ApiError;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.List;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Registration and execution share one policy; the probe consumes the pinned resolution. */
@Component
public class TargetUrlPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws java.net.UnknownHostException; }
    private final Resolver resolver;
    private final List<Cidr> envelope;
    public TargetUrlPolicy() { this(InetAddress::getAllByName); }
    public TargetUrlPolicy(Resolver resolver) { this(resolver,List.of()); }
    public TargetUrlPolicy(Resolver resolver,List<String> deploymentCidrs) {
        this.resolver=resolver;this.envelope=deploymentCidrs.stream().map(Cidr::parse).toList();
        if(envelope.stream().anyMatch(c->!c.isPrivate()))throw new IllegalArgumentException("Private egress envelope must contain private CIDRs only");
    }
    @Autowired public TargetUrlPolicy(@org.springframework.beans.factory.annotation.Qualifier("safeDns") SafeDns dns,@Value("${agentops.monitoring.allowed-private-cidrs:}") String cidrs){
        this(dns,Arrays.stream(cidrs.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());
    }
    public record Endpoint(URI uri,InetAddress[] addresses){
        public Endpoint {addresses=addresses.clone();}
        @Override public InetAddress[] addresses(){return addresses.clone();}
    }
    public List<String> validateCidrs(List<String> cidrs){
        if(cidrs==null||cidrs.size()>16)throw blocked();
        return cidrs.stream().map(Cidr::parse).peek(c->{if(!c.isPrivate()||envelope.stream().noneMatch(e->e.contains(c)))throw ApiError.badRequest("TARGET_NETWORK_NOT_ALLOWED");}).map(Cidr::toString).distinct().toList();
    }
    public String validate(String value) {
        return resolve(value,false,List.of()).uri().toASCIIString();
    }
    public Endpoint resolve(String value,boolean allowHttp,List<String> privateCidrs) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            boolean https="https".equals(uri.getScheme()),http="http".equals(uri.getScheme())&&allowHttp;
            if ((!https&&!http) || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != (https?443:80)) || value.contains("%")
                || value.contains("\\") || value.length() > 2048) throw blocked();
            host = host.toLowerCase(Locale.ROOT);
            if (!host.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}") || host.length() > 253) throw blocked();
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw blocked();
            List<Cidr> approved=validateCidrs(privateCidrs).stream().map(Cidr::parse).toList();
            for (InetAddress address : addresses) if (isMetadata(address) || !isPublic(address) && !(isPrivate(address)&&approved.stream().anyMatch(c->c.contains(address)))) throw blocked();
            return new Endpoint(new URI(uri.getScheme(), null, host, -1, uri.getPath(), null, null).normalize(),addresses);
        } catch (java.net.URISyntaxException | java.net.UnknownHostException | IllegalArgumentException e) { throw blocked(); }
    }
    private static boolean isPrivate(InetAddress address){byte[] b=address.getAddress();int a=b[0]&255,s=b[1]&255;return b.length==4?(a==10||a==172&&s>=16&&s<=31||a==192&&s==168):((a&254)==252);}
    private static boolean isMetadata(InetAddress address){return Arrays.equals(address.getAddress(),new byte[]{(byte)0xfd,0,0x0e,(byte)0xc2,0,0,0,0,0,0,0,0,0,0,2,0x54});}
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && (b == 0 || b == 168)) || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113)
                || (a == 168 && b == 63 && c == 129 && (bytes[3] & 255) == 16));
        }
        // Only global-unicast IPv6; exclude documentation and address-translation/tunnel ranges.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
            && !(a == 0x3f && b == 0xff && (bytes[2] & 0xf0) == 0)
            && !(a == 0x20 && b == 0x01 && (bytes[2] & 0xfe) == 0)
            && !(a == 0x20 && b == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8);
    }
    private static ApiError blocked() { return ApiError.badRequest("TARGET_URL_BLOCKED"); }
}
