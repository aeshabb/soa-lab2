package ru.itmo.soa;

import jakarta.json.JsonArray;

public class Fault extends RuntimeException {
    public final int status;
    public final JsonArray violations;
    public Fault(int status, String message) { this(status, message, null); }
    public Fault(int status, String message, JsonArray violations) {
        super(message); this.status = status; this.violations = violations;
    }
}
