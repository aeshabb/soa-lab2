package ru.itmo.soa.tickets;

import jakarta.json.*;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import ru.itmo.soa.*;

public final class Selection {
    private Selection() {}
    private static final Set<String> FIELDS = Set.of("id","name","coordinateX","coordinateY","creationDate","price","comment","type","venueId","venueName","venueCapacity","venueType","personId");
    private static final Set<String> INTEGERS = Set.of("id","coordinateX","venueId","venueCapacity","personId");
    private static final Set<String> POSITIVE = Set.of("id","venueId","venueCapacity","personId","price");
    private static final Set<String> NUMBERS = Set.of("id","coordinateX","coordinateY","price","venueId","venueCapacity","personId");
    private static final Set<String> STRINGS = Set.of("name","comment","venueName");

    public static JsonObject page(List<JsonObject> source, MultivaluedMap<String,String> query) {
        List<Predicate<JsonObject>> filters = new ArrayList<>();
        int page = 1, size = 20;
        Comparator<JsonObject> order = Comparator.comparingInt(t -> t.getInt("id"));
        for (Map.Entry<String,List<String>> e : query.entrySet()) {
            if (e.getValue().size() != 1) throw new Fault(400,"Параметр " + e.getKey() + " указан несколько раз");
            String key = e.getKey(), raw = e.getValue().get(0);
            if (key.equals("page")) { page = Api.positiveId(raw,"page"); continue; }
            if (key.equals("size")) {
                size = Api.positiveId(raw,"size"); if (size > 100) throw new Fault(400,"size должен быть от 1 до 100"); continue;
            }
            if (key.equals("sort")) {
                Comparator<JsonObject> combined = null;
                for (String part : raw.split(",",-1)) {
                    boolean descending = part.startsWith("-");
                    String field = descending ? part.substring(1) : part;
                    if (!FIELDS.contains(field)) throw new Fault(400,"Недопустимое имя поля в sort: " + field);
                    Comparator<JsonObject> comparator = (a,b) -> compare(field(a,field),field(b,field));
                    if (descending) comparator = comparator.reversed();
                    combined = combined == null ? comparator : combined.thenComparing(comparator);
                }
                order = combined.thenComparingInt(t -> t.getInt("id")); continue;
            }
            String mode = "equal", field = key;
            for (String suffix : new String[]{"Contains","From","To"}) {
                if (key.endsWith(suffix)) { mode = suffix; field = key.substring(0,key.length()-suffix.length()); break; }
            }
            if (!FIELDS.contains(field) || (mode.equals("Contains") && !STRINGS.contains(field))
                    || ((mode.equals("From") || mode.equals("To")) && !NUMBERS.contains(field) && !field.equals("creationDate")))
                throw new Fault(400,"Недопустимый параметр фильтрации: " + key);
            Object wanted = parse(field,raw);
            final String selected = field, operation = mode;
            filters.add(ticket -> {
                Object actual = field(ticket,selected);
                if (actual == null) return false;
                if (operation.equals("Contains")) return ((String)actual).toLowerCase(Locale.ROOT).contains(((String)wanted).toLowerCase(Locale.ROOT));
                int comparison = compare(actual,wanted);
                return operation.equals("From") ? comparison >= 0 : operation.equals("To") ? comparison <= 0 : comparison == 0;
            });
        }
        List<JsonObject> matching = source.stream().filter(t -> filters.stream().allMatch(p -> p.test(t))).sorted(order).toList();
        long start = (long)(page-1)*size;
        JsonArrayBuilder items = Json.createArrayBuilder();
        if (start < matching.size()) matching.subList((int)start,(int)Math.min(start+size,matching.size())).forEach(items::add);
        return Json.createObjectBuilder().add("items",items).add("page",page).add("size",size)
                .add("totalElements",matching.size()).add("totalPages",(matching.size()+size-1)/size).build();
    }
    private static Object parse(String field, String raw) {
        try {
            if (INTEGERS.contains(field)) {
                if (!raw.matches("[+-]?\\d+")) throw new IllegalArgumentException();
                int value = Integer.parseInt(raw);
                if (POSITIVE.contains(field) && value <= 0) throw new IllegalArgumentException();
                return (double)value;
            }
            if (field.equals("price")) return (double)Api.price(raw);
            if (field.equals("coordinateY")) {
                Api.numeric(raw);
                double value = Double.parseDouble(raw); if (!Double.isFinite(value)) throw new IllegalArgumentException(); return value;
            }
            if (field.equals("creationDate")) {
                if (!raw.matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}")) throw new IllegalArgumentException();
                return LocalDateTime.parse(raw,Api.DATE);
            }
            if (field.equals("type") && !Validation.TICKET_TYPES.contains(raw)) throw new IllegalArgumentException();
            if (field.equals("venueType") && !Validation.VENUE_TYPES.contains(raw)) throw new IllegalArgumentException();
            if ((field.equals("name") || field.equals("venueName")) && raw.isEmpty()) throw new IllegalArgumentException();
            return raw;
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new Fault(400,"Некорректное значение параметра " + field);
        }
    }
    private static Object field(JsonObject ticket, String field) {
        JsonValue v = switch (field) {
            case "coordinateX" -> ticket.getJsonObject("coordinates").get("x");
            case "coordinateY" -> ticket.getJsonObject("coordinates").get("y");
            case "venueId" -> ticket.getJsonObject("venue").get("id");
            case "venueName" -> ticket.getJsonObject("venue").get("name");
            case "venueCapacity" -> ticket.getJsonObject("venue").get("capacity");
            case "venueType" -> ticket.getJsonObject("venue").get("type");
            default -> ticket.get(field);
        };
        if (v == null || v == JsonValue.NULL) return null;
        if (v instanceof JsonNumber n) return n.doubleValue();
        String s = ((JsonString)v).getString();
        return field.equals("creationDate") ? LocalDateTime.parse(s,Api.DATE) : s;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        return ((Comparable)a).compareTo(b);
    }
}
