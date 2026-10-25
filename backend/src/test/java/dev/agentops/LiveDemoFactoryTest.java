package dev.agentops;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LiveDemoFactoryTest {
    @Test void isolatedLiveFactoryStartsWithRestrictedCheckpointAndDoesNotCallModelOnRead()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();
        Path directory=root.resolve(".cache/demo/unit-live-java-"+UUID.randomUUID());
        Files.createDirectories(directory);
        String token="fixture-internal-"+UUID.randomUUID();
        try(var pg=PostgresFixture.start();var python=PythonFixture.startLiveDeepSeek(pg,token,directory)){
            var http=HttpClient.newHttpClient();
            var uri=URI.create("http://127.0.0.1:"+python.port+"/internal/v1/runs/"+UUID.randomUUID());
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).header("X-Request-Id",UUID.randomUUID().toString()).build();
            assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(http.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(Files.exists(directory.resolve("live-model-call.json"))).isFalse();
        }finally{Files.deleteIfExists(directory);}
    }
}
