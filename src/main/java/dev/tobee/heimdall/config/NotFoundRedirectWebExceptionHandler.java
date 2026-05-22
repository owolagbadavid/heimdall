package dev.tobee.heimdall.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Redirects requests that match no route (404) to the Swagger UI.
 *
 * <p>When the {@code DispatcherHandler} finds no handler for a request it emits a
 * {@link ResponseStatusException} with {@link HttpStatus#NOT_FOUND}. This handler runs
 * ahead of the default error handlers (order {@code -2}) and turns that case into a
 * redirect; any other error is passed on unchanged.
 */
@Component
@Order(-2)
public class NotFoundRedirectWebExceptionHandler implements WebExceptionHandler {

    private final URI swaggerUi;

    public NotFoundRedirectWebExceptionHandler(
            @Value("${springdoc.swagger-ui.path:/swagger-ui.html}") String swaggerUiPath) {
        this.swaggerUi = URI.create(swaggerUiPath);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof ResponseStatusException rse
                && rse.getStatusCode() == HttpStatus.NOT_FOUND
                && !exchange.getResponse().isCommitted()) {
            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
            exchange.getResponse().getHeaders().setLocation(swaggerUi);
            return exchange.getResponse().setComplete();
        }
        return Mono.error(ex);
    }
}
