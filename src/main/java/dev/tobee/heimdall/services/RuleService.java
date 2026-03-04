package dev.tobee.heimdall.services;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.repositories.RuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository ruleRepository;

    @Transactional(readOnly = true)
    public List<Rule> findAll() {
        return ruleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Rule> findById(String id) {
        return ruleRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Rule> findByApiAndOp(String api, String op) {
        return ruleRepository.findByApiAndOp(api, op);
    }

    @Transactional(readOnly = true)
    public List<Rule> findByApi(String api) {
        return ruleRepository.findByApi(api);
    }

    @Transactional
    public Rule save(Rule rule) {
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteById(String id) {
        ruleRepository.deleteById(id);
    }
}
