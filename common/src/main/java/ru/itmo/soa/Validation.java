package ru.itmo.soa;

import jakarta.json.*;
import java.util.Set;

public final class Validation {
    private Validation() {}
    public static final Set<String> TICKET_TYPES = Set.of("VIP", "USUAL", "BUDGETARY", "CHEAP");
    public static final Set<String> VENUE_TYPES = Set.of("BAR", "CINEMA", "MALL");

    public static JsonObject ticket(JsonObject input) {
        JsonArrayBuilder errors = Json.createArrayBuilder();
        text(input,"name","name",false,errors);
        text(input,"comment","comment",true,errors);
        enumeration(input,"type","type",TICKET_TYPES,false,errors);
        number(input,"price","price",false,false,true,true,errors);
        number(input,"personId","personId",true,true,true,false,errors);
        for (String key : new String[]{"id","creationDate"})
            if (input.containsKey(key)) violation(errors,key,"поле генерируется сервером и не принимается",input.get(key));
        JsonObject coordinates = child(input,"coordinates",errors);
        if (coordinates != null) {
            number(coordinates,"x","coordinates.x",false,true,false,false,errors);
            number(coordinates,"y","coordinates.y",false,false,false,false,errors);
        }
        JsonObject venue = child(input,"venue",errors);
        if (venue != null) {
            text(venue,"name","venue.name",false,errors);
            number(venue,"capacity","venue.capacity",false,true,true,false,errors);
            enumeration(venue,"type","venue.type",VENUE_TYPES,true,errors);
            if (venue.containsKey("id")) violation(errors,"venue.id","поле генерируется сервером и не принимается",venue.get("id"));
        }
        JsonArray violations = errors.build();
        if (!violations.isEmpty()) throw new Fault(422,"Нарушены ограничения целостности объекта Ticket",violations);
        JsonObject normalizedVenue = Json.createObjectBuilder().add("name",venue.getString("name"))
                .add("capacity",venue.getInt("capacity")).add("type",Api.value(venue,"type")).build();
        return Json.createObjectBuilder().add("name",input.getString("name"))
                .add("coordinates",Json.createObjectBuilder().add("x",coordinates.getInt("x"))
                    .add("y",coordinates.getJsonNumber("y").doubleValue()))
                .add("price",(double)input.getJsonNumber("price").bigDecimalValue().floatValue())
                .add("comment",Api.value(input,"comment")).add("type",input.getString("type"))
                .add("venue",normalizedVenue).add("personId",Api.value(input,"personId")).build();
    }
    private static JsonObject child(JsonObject input, String key, JsonArrayBuilder errors) {
        JsonValue v = Api.value(input,key);
        if (v instanceof JsonObject object) return object;
        violation(errors,key,"требуется непустой объект",v); return null;
    }
    private static void text(JsonObject input, String key, String path, boolean nullable, JsonArrayBuilder errors) {
        JsonValue v = Api.value(input,key);
        if (nullable && v == JsonValue.NULL) return;
        if (!(v instanceof JsonString s) || (!nullable && s.getString().isEmpty()))
            violation(errors,path,nullable ? "требуется строка или null" : "требуется строка длиной не менее 1",v);
    }
    private static void enumeration(JsonObject input, String key, String path, Set<String> choices, boolean nullable, JsonArrayBuilder errors) {
        JsonValue v = Api.value(input,key);
        if (nullable && v == JsonValue.NULL) return;
        if (!(v instanceof JsonString s) || !choices.contains(s.getString()))
            violation(errors,path,"допустимые значения: " + choices,v);
    }
    private static void number(JsonObject input, String key, String path, boolean nullable, boolean integer,
                               boolean positive, boolean single, JsonArrayBuilder errors) {
        JsonValue v = Api.value(input,key);
        if (nullable && v == JsonValue.NULL) return;
        boolean valid = v instanceof JsonNumber;
        if (v instanceof JsonNumber n) {
            double d = n.doubleValue();
            valid = Double.isFinite(d) && (!positive || d > 0);
            if (integer) {
                try { n.intValueExact(); } catch (ArithmeticException e) { valid = false; }
            }
            if (single) valid = valid && Float.isFinite((float)d) && (float)d > 0;
        }
        if (!valid) violation(errors,path,"требуется " + (integer ? "целое int32" : single ? "конечное float" : "конечное double")
                + (positive ? " больше 0" : ""),v);
    }
    public static void violation(JsonArrayBuilder errors, String field, String message, JsonValue value) {
        JsonObjectBuilder v = Json.createObjectBuilder().add("field",field).add("message",message);
        if (value == JsonValue.NULL) v.addNull("rejectedValue"); else v.add("rejectedValue",value.toString());
        errors.add(v);
    }
}
