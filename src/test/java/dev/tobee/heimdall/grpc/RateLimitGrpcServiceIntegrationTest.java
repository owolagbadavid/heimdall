package dev.tobee.heimdall.grpc;

import dev.tobee.heimdall.AbstractIntegrationTest;
import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.RuleCacheRepository;
import dev.tobee.heimdall.repositories.RuleRepository;
import dev.tobee.heimdall.repositories.TokenRepository;
import dev.tobee.heimdall.services.RuleService;
import dev.tobee.heimdall.services.grpc.RateLimitRequest;
import dev.tobee.heimdall.services.grpc.RateLimitResponse;
import dev.tobee.heimdall.services.grpc.RateLimitServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the gRPC RateLimitService.
 *
 * The gRPC server binds to port 9091 (set via GRPC_PORT in
 * AbstractIntegrationTest.configureProperties).
 */
class RateLimitGrpcServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired RuleService ruleService;
    @Autowired RuleCacheRepository ruleCacheRepository;
    @Autowired RuleRepository ruleRepository;
    @Autowired TokenRepository tokenRepository;

    private static final String API = "grpc-test-api";
    private static final String OP  = "grpc-test-op";

    private static ManagedChannel channel;
    private static RateLimitServiceGrpc.RateLimitServiceBlockingStub stub;

    @BeforeAll
    static void openChannel() {
        // Port 9091 is set via GRPC_PORT in AbstractIntegrationTest.configureProperties
        channel = ManagedChannelBuilder.forAddress("localhost", 9091)
                .usePlaintext()
                .build();
        stub = RateLimitServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @BeforeEach
    void clean() {
        ruleRepository.deleteAll().block();
        ruleCacheRepository.evict(API, OP).block();
        tokenRepository.delete("heimdall:tokens:" + API + ":" + OP + ":grpc-user-1").block();
        tokenRepository.delete("heimdall:tokens:" + API + ":" + OP + ":grpc-user-2").block();
    }

    @Test
    void checkRateLimit_noRule_notAllowed() {
        RateLimitResponse response = stub.checkRateLimit(
                RateLimitRequest.newBuilder()
                        .setApi(API).setOp(OP).setKey("grpc-user-1")
                        .build());

        assertThat(response.getAllowed()).isFalse();
        assertThat(response.getRemainingTokens()).isEqualTo(0); // -2 clamped to 0
    }

    @Test
    void checkRateLimit_ruleExists_firstRequest_allowed() {
        createRule(API, OP, 5, 60);

        RateLimitResponse response = stub.checkRateLimit(
                RateLimitRequest.newBuilder()
                        .setApi(API).setOp(OP).setKey("grpc-user-1")
                        .build());

        assertThat(response.getAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(4); // 5 - 1
    }

    @Test
    void checkRateLimit_exhaustedBucket_notAllowed() {
        createRule(API, OP, 2, 60);

        stub.checkRateLimit(RateLimitRequest.newBuilder().setApi(API).setOp(OP).setKey("grpc-user-1").build());
        stub.checkRateLimit(RateLimitRequest.newBuilder().setApi(API).setOp(OP).setKey("grpc-user-1").build());

        RateLimitResponse denied = stub.checkRateLimit(
                RateLimitRequest.newBuilder().setApi(API).setOp(OP).setKey("grpc-user-1").build());

        assertThat(denied.getAllowed()).isFalse();
        assertThat(denied.getRemainingTokens()).isEqualTo(0);
    }

    @Test
    void checkRateLimit_differentCallerKeys_independentBuckets() {
        createRule(API, OP, 1, 60);

        RateLimitResponse user1 = stub.checkRateLimit(
                RateLimitRequest.newBuilder().setApi(API).setOp(OP).setKey("grpc-user-1").build());
        RateLimitResponse user2 = stub.checkRateLimit(
                RateLimitRequest.newBuilder().setApi(API).setOp(OP).setKey("grpc-user-2").build());

        assertThat(user1.getAllowed()).isTrue();
        assertThat(user2.getAllowed()).isTrue();
    }

    private void createRule(String api, String op, int rateLimit, int windowSecs) {
        Rule rule = new Rule();
        rule.setName("grpc-test");
        rule.setApi(api);
        rule.setOp(op);
        rule.setRateLimit(rateLimit);
        rule.setTimeInSeconds(windowSecs);
        ruleService.create(rule).block();
    }
}
