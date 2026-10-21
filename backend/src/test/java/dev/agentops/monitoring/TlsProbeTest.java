package dev.agentops.monitoring;

import com.sun.net.httpserver.*;
import dev.agentops.targets.TargetUrlPolicy;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class TlsProbeTest {
    static HttpsServer server;static ExecutorService workers;static SSLContext trusted;static Path directory,keystore;
    @BeforeAll static void start()throws Exception{
        directory=Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"tls-fixture-");keystore=directory.resolve("fixture.p12");
        String password=UUID.randomUUID().toString();String keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",keystore.toString(),"-storepass",password,"-keypass",password,"-dname","CN=health.fixture.test","-ext","SAN=dns:health.fixture.test","-validity","1","-noprompt").redirectErrorStream(true).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0){process.destroyForcibly();throw new IllegalStateException("Fixture certificate generation failed");}
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(keystore)){keys.load(in,password.toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        var serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,new SecureRandom());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);trusted=SSLContext.getInstance("TLS");trusted.init(null,tm.getTrustManagers(),new SecureRandom());
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/health",e->{e.sendResponseHeaders(200,-1);e.close();});workers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(workers);server.start();
    }
    @AfterAll static void close()throws Exception{if(server!=null)server.stop(0);if(workers!=null)workers.close();if(keystore!=null)Files.deleteIfExists(keystore);if(directory!=null)Files.deleteIfExists(directory);}
    TargetUrlPolicy.Endpoint endpoint(String hostname)throws Exception{return new TargetUrlPolicy.Endpoint(URI.create("https://"+hostname+":"+server.getAddress().getPort()+"/health"),new InetAddress[]{InetAddress.getLoopbackAddress()});}
    @Test void pinningPreservesHostnameVerificationEvenWhenCertificateIsTrusted()throws Exception{
        try(var probe=new SafeHttpProbe(trusted)){
            assertThat(probe.execute(endpoint("health.fixture.test"),"GET",List.of(200),3000).outcome()).isEqualTo("UP");
            assertThat(probe.execute(endpoint("wrong.fixture.test"),"GET",List.of(200),3000).errorCode()).isEqualTo("TLS_ERROR");
        }
    }
    @Test void productionTrustDoesNotAcceptFixtureSelfSignedCertificate()throws Exception{
        try(var probe=new SafeHttpProbe()){assertThat(probe.execute(endpoint("health.fixture.test"),"GET",List.of(200),3000).errorCode()).isEqualTo("TLS_ERROR");}
    }
}
