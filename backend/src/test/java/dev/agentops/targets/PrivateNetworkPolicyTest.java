package dev.agentops.targets;

import dev.agentops.shared.ApiError;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PrivateNetworkPolicyTest {
    InetAddress ip(String value)throws java.net.UnknownHostException{return InetAddress.getByName(value);}
    @Test void documentationIpv6RangeIsNeverAProbeDestination()throws Exception{
        for(String address:List.of("3fff::1","3fff:fff:ffff::1")){
            var policy=new TargetUrlPolicy(h->new InetAddress[]{ip(address)});
            assertThatThrownBy(()->policy.resolve("https://health.example.com/",false,List.of())).as(address).isInstanceOf(ApiError.class);
        }
        var mixed=new TargetUrlPolicy(h->new InetAddress[]{ip("2606:4700:4700::1111"),ip("3fff::1")});
        assertThatThrownBy(()->mixed.resolve("https://health.example.com/",false,List.of())).isInstanceOf(ApiError.class);
    }
    @Test void approvedPrivateCidrMustFitDeploymentEnvelope()throws Exception{
        var policy=new TargetUrlPolicy(h->new InetAddress[]{ip("10.20.1.4")},List.of("10.20.0.0/16"));
        assertThat(policy.resolve("https://health.example.com/",false,List.of("10.20.1.0/24")).addresses()).hasSize(1);
        assertThatThrownBy(()->policy.resolve("https://health.example.com/",false,List.of())).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->policy.validateCidrs(List.of("10.0.0.0/8"))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->policy.validateCidrs(List.of("127.0.0.0/8"))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->policy.validateCidrs(List.of("localhost/32"))).isInstanceOf(ApiError.class);
    }
    @Test void ipv6MetadataIsForbiddenEvenInsideApprovedPrivateRange()throws Exception{
        var policy=new TargetUrlPolicy(h->new InetAddress[]{ip("fd00:ec2::254")},List.of("fd00::/8"));
        assertThatThrownBy(()->policy.resolve("https://health.example.com/",false,List.of("fd00:ec2::/64"))).isInstanceOf(ApiError.class);
    }
    @Test void everyResolutionAndEveryAddressMustBeRevalidated()throws Exception{
        AtomicInteger lookups=new AtomicInteger();
        var policy=new TargetUrlPolicy(h->new InetAddress[]{ip(lookups.getAndIncrement()==0?"93.184.216.34":"127.0.0.1")});
        assertThat(policy.validate("https://health.example.com")).isNotBlank();
        assertThatThrownBy(()->policy.resolve("https://health.example.com",false,List.of())).isInstanceOf(ApiError.class);
        var mixed=new TargetUrlPolicy(h->new InetAddress[]{ip("93.184.216.34"),ip("10.99.1.1")},List.of("10.20.0.0/16"));
        assertThatThrownBy(()->mixed.resolve("https://health.example.com",false,List.of("10.20.0.0/16"))).isInstanceOf(ApiError.class);
    }
    @Test void httpNeedsExplicitApprovalAndCidrParsingDoesNotAcceptAmbiguousLiterals()throws Exception{
        var policy=new TargetUrlPolicy(h->new InetAddress[]{ip("93.184.216.34")});
        assertThatThrownBy(()->policy.resolve("http://health.example.com",false,List.of())).isInstanceOf(ApiError.class);
        assertThat(policy.resolve("http://health.example.com",true,List.of()).uri().getScheme()).isEqualTo("http");
        for(String invalid:List.of("010.0.0.0/8","10.0.0.0/0","169.254.0.0/16","0.0.0.0/0","::/0","::ffff:10.0.0.0/104"))
            assertThatThrownBy(()->policy.validateCidrs(List.of(invalid))).as(invalid).isInstanceOf(ApiError.class);
    }
}
