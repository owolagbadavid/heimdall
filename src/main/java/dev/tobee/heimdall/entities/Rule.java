package dev.tobee.heimdall.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rules")
@Getter
@Setter
public class Rule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;
    private String api;
    private String op;
    private String timeInSeconds;
    private String rateLimit;

    @Version
    private int version;
}
