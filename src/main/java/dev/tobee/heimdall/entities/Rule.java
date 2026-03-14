package dev.tobee.heimdall.entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("rules")
@Getter
@Setter
public class Rule {
    @Id
    private String id;

    private String name;

    private String api;

    private String op;
    private long timeInSeconds;
    private long rateLimit;

    @Version
    private long version;
}
