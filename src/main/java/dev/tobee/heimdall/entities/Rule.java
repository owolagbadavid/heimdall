package dev.tobee.heimdall.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"api", "op"})
})
@Getter
@Setter
public class Rule {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private String id;

    private String name;

    @Column(nullable = false)
    private String api;

    @Column(nullable = false)
    private String op;
    private long timeInSeconds;
    private long rateLimit;

    @Version
    private long version;
}
