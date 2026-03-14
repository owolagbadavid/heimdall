package dev.tobee.heimdall.repositories;

import dev.tobee.heimdall.entities.Rule;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RuleRepository extends ReactiveCrudRepository<Rule, String> {

    Mono<Rule> findByApiAndOp(String api, String op);

    Flux<Rule> findByApi(String api);

    @Query("SELECT * FROM rules ORDER BY id ASC LIMIT :limit")
    Flux<Rule> findAllByOrderByIdAsc(int limit);

    @Query("SELECT * FROM rules WHERE id > :id ORDER BY id ASC LIMIT :limit")
    Flux<Rule> findByIdGreaterThanOrderByIdAsc(String id, int limit);

    @Query("SELECT * FROM rules ORDER BY id ASC LIMIT :limit OFFSET :offset")
    Flux<Rule> findAllOrderedPaged(int limit, long offset);
}
