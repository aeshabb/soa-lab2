package ru.itmo.soa;

import jakarta.json.*;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

public final class Api {
    private Api() {}
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    public static String now() { return LocalDateTime.now().format(DATE); }
    public static Response json(int status, JsonValue value) {
        return Response.status(status).type("application/json").entity(value.toString()).build();
    }
    public static JsonObject object(String body) {
        if (body == null || body.isBlank()) throw new Fault(400, "Требуется JSON-объект");
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException e) { throw new Fault(400, "Некорректный JSON-объект"); }
    }
    public static int positiveId(String raw, String field) {
        try {
            if (!raw.matches("[+-]?\\d+")) throw new NumberFormatException();
            int value = Integer.parseInt(raw);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) { }
        throw new Fault(400, field + " должен быть целым числом больше 0 (int32)");
    }
    public static float price(String raw) {
        try {
            numeric(raw);
            float value = Float.parseFloat(raw);
            if (Float.isFinite(value) && value > 0) return value;
        } catch (NumberFormatException ignored) { }
        throw new Fault(400, "Цена должна быть конечным числом больше 0 (float)");
    }
    public static void numeric(String raw) {
        // Java parseFloat/parseDouble принимают суффикс F и hex-литералы,
        // которые не являются десятичными числовыми параметрами REST API.
        if (!raw.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"))
            throw new NumberFormatException();
    }
    public static void contentType(String type) {
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new Fault(415, "Тело запроса должно передаваться в формате application/json");
    }
    public static JsonValue value(JsonObject object, String key) {
        return object.getOrDefault(key, JsonValue.NULL);
    }
    public static JsonObject input(JsonObject ticket) {
        return Json.createObjectBuilder(ticket).remove("id").remove("creationDate")
                .add("venue", Json.createObjectBuilder(ticket.getJsonObject("venue")).remove("id")).build();
    }
}
