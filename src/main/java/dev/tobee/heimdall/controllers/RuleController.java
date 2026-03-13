package dev.tobee.heimdall.controllers;

import dev.tobee.heimdall.entities.Rule;
import dev.tobee.heimdall.services.RuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
@Tag(name = "Rules", description = "Rate-limit rule management")
public class RuleController {

	private final RuleService ruleService;

	@Operation(summary = "Create a rule", responses = {
			@ApiResponse(responseCode = "201", description = "Rule created",
					content = @Content(schema = @Schema(implementation = RuleResponse.class)))
	})
	@PostMapping
	public Mono<ResponseEntity<RuleResponse>> createRule(@RequestBody RuleUpsertRequest request) {
		return ruleService.create(toRule(request))
				.map(created -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created)));
	}

	@Operation(summary = "Update a rule", responses = {
			@ApiResponse(responseCode = "200", description = "Rule updated"),
			@ApiResponse(responseCode = "404", description = "Rule not found", content = @Content)
	})
	@PutMapping("/{id}")
	public Mono<ResponseEntity<RuleResponse>> updateRule(@PathVariable String id, @RequestBody RuleUpsertRequest request) {
		return ruleService.update(id, toRule(request))
				.map(rule -> ResponseEntity.ok(toResponse(rule)))
				.defaultIfEmpty(ResponseEntity.notFound().build());
	}

	@Operation(summary = "Get a single rule by ID", responses = {
			@ApiResponse(responseCode = "200", description = "Rule found"),
			@ApiResponse(responseCode = "404", description = "Rule not found", content = @Content)
	})
	@GetMapping("/{id}")
	public Mono<ResponseEntity<RuleResponse>> getRule(@PathVariable String id) {
		return ruleService.findById(id)
				.map(rule -> ResponseEntity.ok(toResponse(rule)))
				.defaultIfEmpty(ResponseEntity.notFound().build());
	}

	@Operation(summary = "List rules with token pagination")
	@GetMapping
	public Mono<RulePageResponse> getRules(
			@Parameter(description = "Max items per page (1-100)") @RequestParam(defaultValue = "20") int limit,
			@Parameter(description = "Cursor token from a previous response") @RequestParam(required = false) String nextToken
	) {
		return ruleService.getPagedRules(limit, nextToken)
				.map(page -> {
					List<RuleResponse> items = page.items().stream().map(this::toResponse).toList();
					return new RulePageResponse(items, page.nextToken());
				});
	}

	@Operation(summary = "Delete a rule", responses = {
			@ApiResponse(responseCode = "204", description = "Rule deleted"),
			@ApiResponse(responseCode = "404", description = "Rule not found", content = @Content)
	})
	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<Void>> deleteRule(@PathVariable String id) {
		return ruleService.delete(id)
				.map(deleted -> deleted
						? ResponseEntity.noContent().<Void>build()
						: ResponseEntity.notFound().<Void>build());
	}

	private Rule toRule(RuleUpsertRequest request) {
		Rule rule = new Rule();
		rule.setName(request.name());
		rule.setApi(request.api());
		rule.setOp(request.op());
		rule.setTimeInSeconds(request.timeInSeconds());
		rule.setRateLimit(request.rateLimit());
		return rule;
	}

	private RuleResponse toResponse(Rule rule) {
		return new RuleResponse(
				rule.getId(),
				rule.getName(),
				rule.getApi(),
				rule.getOp(),
				rule.getTimeInSeconds(),
				rule.getRateLimit(),
				rule.getVersion()
		);
	}

	public record RuleUpsertRequest(
			String name,
			String api,
			String op,
			long timeInSeconds,
			long rateLimit
	) {}

	public record RuleResponse(
			String id,
			String name,
			String api,
			String op,
			long timeInSeconds,
			long rateLimit,
			long version
	) {}

	public record RulePageResponse(
			List<RuleResponse> items,
			String nextToken
	) {}
}
