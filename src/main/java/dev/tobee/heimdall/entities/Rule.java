package dev.tobee.heimdall.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "rules")
@Getter
@Setter
public class Rule {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private String id;

    private String name;
    private String api;
    private String op;
    private long timeInSeconds;
    private long rateLimit;

    @Version
    private long version;
}
