package dev.tobee.heimdall.services.grpc;

import dev.tobee.heimdall.services.TokenService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class RateLimitGrpcService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final TokenService tokenService;

    @Override
    public void checkRateLimit(RateLimitRequest request, StreamObserver<RateLimitResponse> responseObserver) {
        String api = request.getApi();
        String op = request.getOp();
        String key = request.getKey();

        long remaining = tokenService.tryConsume(api, op, key);
        boolean allowed = remaining >= 0;

        RateLimitResponse response = RateLimitResponse.newBuilder()
                .setAllowed(allowed)
                .setRemainingTokens(Math.max(remaining, 0))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
