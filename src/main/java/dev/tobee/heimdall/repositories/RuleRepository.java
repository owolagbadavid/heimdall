package dev.tobee.heimdall.repositories;

import dev.tobee.heimdall.entities.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuleRepository extends JpaRepository<Rule, String> {

    Optional<Rule> findByApiAndOp(String api, String op);

    List<Rule> findByApi(String api);
}
