package dev.tobee.heimdall.controllers;

import dev.tobee.heimdall.AbstractWebIntegrationTest;
import dev.tobee.heimdall.repositories.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;


import static org.assertj.core.api.Assertions.*;

class RuleControllerIntegrationTest extends AbstractWebIntegrationTest {

    @Autowired RuleRepository ruleRepository;

    @BeforeEach
    void clean() {
        ruleRepository.deleteAll().block();
    }

    @Test
    void createRule_validRequest_returns201WithBody() {
        webTestClient.post().uri("/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"test","api":"pay","op":"charge",
                         "timeInSeconds":60,"rateLimit":100}""")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.api").isEqualTo("pay")
                .jsonPath("$.op").isEqualTo("charge")
                .jsonPath("$.rateLimit").isEqualTo(100)
                .jsonPath("$.timeInSeconds").isEqualTo(60)
                .jsonPath("$.version").isNumber();
    }

    @Test
    void createRule_idIsAssignedByServer() {
        String id1 = extractId(createRule("api1", "op1"));
        String id2 = extractId(createRule("api2", "op2"));
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).isNotBlank();
    }

    @Test
    void getRule_existingId_returns200() {
        String id = extractId(createRule("pay", "charge"));

        webTestClient.get().uri("/rules/" + id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.api").isEqualTo("pay");
    }

    @Test
    void getRule_unknownId_returns404() {
        webTestClient.get().uri("/rules/does-not-exist")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void updateRule_existingId_returns200WithUpdatedFields() {
        String id = extractId(createRule("pay", "charge"));

        webTestClient.put().uri("/rules/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"updated","api":"pay","op":"charge",
                         "timeInSeconds":120,"rateLimit":200}""")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.rateLimit").isEqualTo(200)
                .jsonPath("$.timeInSeconds").isEqualTo(120)
                .jsonPath("$.name").isEqualTo("updated");
    }

    @Test
    void updateRule_unknownId_returns404() {
        webTestClient.put().uri("/rules/missing")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"x","api":"a","op":"b",
                         "timeInSeconds":60,"rateLimit":10}""")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteRule_existingId_returns204() {
        String id = extractId(createRule("pay", "charge"));

        webTestClient.delete().uri("/rules/" + id)
                .exchange()
                .expectStatus().isNoContent();

        // Confirm it's gone
        webTestClient.get().uri("/rules/" + id)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteRule_unknownId_returns404() {
        webTestClient.delete().uri("/rules/missing")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void listRules_emptyDb_returnsEmptyItems() {
        webTestClient.get().uri("/rules")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.nextToken").doesNotExist();
    }

    @Test
    void listRules_singlePage_noNextToken() {
        createRule("api-a", "op-1");
        createRule("api-b", "op-1");

        webTestClient.get().uri("/rules?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.nextToken").doesNotExist();
    }

    @Test
    void listRules_pagination_nextTokenCursorsCorrectly() {
        createRule("a1", "op");
        createRule("a2", "op");
        createRule("a3", "op");

        // Use typed extraction for the cursor
        var firstPage = webTestClient.get().uri("/rules?limit=2")
                .exchange()
                .expectStatus().isOk()
                .expectBody(RuleController.RulePageResponse.class)
                .returnResult().getResponseBody();

        assertThat(firstPage).isNotNull();
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextToken()).isNotBlank();

        // Second page using the cursor
        var secondPage = webTestClient.get()
                .uri("/rules?limit=2&nextToken=" + firstPage.nextToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody(RuleController.RulePageResponse.class)
                .returnResult().getResponseBody();

        assertThat(secondPage).isNotNull();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextToken()).isNull();
    }

    private byte[] createRule(String api, String op) {
        return webTestClient.post().uri("/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"test","api":"%s","op":"%s",
                         "timeInSeconds":60,"rateLimit":10}""".formatted(api, op))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
    }

    private String extractId(byte[] body) {
        // Parse the id field from the JSON response bytes
        String json = new String(body);
        int start = json.indexOf("\"id\":\"") + 6;
        int end   = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
