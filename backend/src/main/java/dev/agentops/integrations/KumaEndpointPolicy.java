package dev.agentops.integrations;

import dev.agentops.shared.ApiError;
import dev.agentops.targets.SafeDns;
import dev.agentops.targets.TargetUrlPolicy;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One deployment-owned origin; user mappings never enlarge the egress envelope. */
@Component
public class KumaEndpointPolicy {
    private final String configured;private final boolean allowHttp;private final TargetUrlPolicy policy;
    @org.springframework.context.annotation.Bean("kumaDns")
    public static SafeDns kumaDns(){return new SafeDns();}
    @Autowired public KumaEndpointPolicy(Environment env,@org.springframework.beans.factory.annotation.Qualifier("kumaDns") SafeDns dns){
        this(env.getProperty("agentops.kuma.base-url",""),env.getProperty("agentops.kuma.allow-http",Boolean.class,false),
            Arrays.stream(env.getProperty("agentops.kuma.allowed-private-cidrs","").split(",")).map(String::trim).filter(s->!s.isEmpty()).toList(),dns);
    }
    public KumaEndpointPolicy(String origin,boolean http,List<String> cidrs,TargetUrlPolicy.Resolver resolver){
        configured=origin;allowHttp=http;policy=new TargetUrlPolicy(resolver,cidrs);this.cidrs=List.copyOf(cidrs);
    }
    private final List<String> cidrs;
    public String validate(String value){
        try{
            URI uri=URI.create(value);
            if(configured.isBlank()||!value.equals(configured)||!List.of("https","http").contains(uri.getScheme())||
                (uri.getScheme().equals("http")&&!allowHttp)||uri.getHost()==null||
                !uri.getHost().matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")||
                uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
                uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()||uri.getPort()==0||uri.getPort()>65535||value.contains("%")||value.contains("\\"))throw blocked();
            return uri.toASCIIString();
        }catch(IllegalArgumentException e){throw blocked();}
    }
    public TargetUrlPolicy.Endpoint resolve(String origin){
        URI uri=URI.create(validate(origin));
        // Reuse the proven IP/CIDR/metadata rules; only the exact deployment origin may choose its port.
        var approved=policy.resolve(uri.getScheme()+"://"+uri.getHost(),allowHttp,cidrs);
        return new TargetUrlPolicy.Endpoint(uri,approved.addresses());
    }
    public static void reference(String value){if(value==null||!value.matches("env:KUMA_PUSH_TOKEN_[A-Z0-9_]{1,80}"))throw ApiError.badRequest("KUMA_SECRET_REFERENCE_INVALID");}
    private static ApiError blocked(){return ApiError.badRequest("KUMA_ORIGIN_BLOCKED");}
}
