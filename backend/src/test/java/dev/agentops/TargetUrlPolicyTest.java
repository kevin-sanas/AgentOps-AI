package dev.agentops;

import dev.agentops.shared.ApiError;
import dev.agentops.targets.TargetUrlPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetUrlPolicyTest {
    static InetAddress ip(String ip) throws UnknownHostException { return InetAddress.getByName(ip); }

    @ParameterizedTest
    @ValueSource(strings={"0.0.0.0", "10.0.0.1", "100.64.0.1", "100.100.100.200", "127.0.0.1", "169.254.169.254", "168.63.129.16", "172.16.0.1", "192.168.1.1", "192.0.0.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fc00::1", "2001:db8::1", "64:ff9b::a00:1", "::ffff:127.0.0.1", "2002:7f00:1::1", "2001:20::1"})
    void blockedDnsAddressesNeverBecomeTargets(String address) {
        var policy = new TargetUrlPolicy(host -> new InetAddress[]{ip(address)});
        assertThatThrownBy(() -> policy.validate("https://fixture.example/health"))
            .isInstanceOf(ApiError.class).hasMessage("TARGET_URL_BLOCKED");
    }

    @Test void mixedPublicAndPrivateDnsAnswerIsRejected() {
        var policy = new TargetUrlPolicy(host -> new InetAddress[]{ip("93.184.216.34"), ip("10.0.0.1")});
        assertThatThrownBy(() -> policy.validate("https://fixture.example/health")).isInstanceOf(ApiError.class);
    }

    @Test void publicIpv4AndIpv6CanBeRegisteredWithoutHttpRequests() {
        var policy = new TargetUrlPolicy(host -> new InetAddress[]{ip("93.184.216.34"), ip("2606:4700:4700::1111")});
        assertThat(policy.validate("https://FIXTURE.example:443/health")).isEqualTo("https://fixture.example/health");
    }

    @Test void dnsFailureIsReportedWithoutHostnameOrCause() {
        var policy = new TargetUrlPolicy(host -> { throw new UnknownHostException("sensitive-internal-host"); });
        assertThatThrownBy(() -> policy.validate("https://fixture.example/health"))
            .isInstanceOf(ApiError.class).hasMessage("TARGET_URL_BLOCKED").hasNoCause();
    }
}
