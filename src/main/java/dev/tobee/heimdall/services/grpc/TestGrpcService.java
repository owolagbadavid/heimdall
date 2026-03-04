package dev.tobee.heimdall.services.grpc;

import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class TestGrpcService extends TestServiceGrpc.TestServiceImplBase {

	@Override
	public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
		PingResponse response = PingResponse.newBuilder()
				.setMessage("Pong: " + request.getMessage())
				.build();

		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}
}
