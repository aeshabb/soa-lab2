package ru.itmo.soa.tickets;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import ru.itmo.soa.*;

@ApplicationScoped
public class TicketStore {
    private Map<Integer, JsonObject> tickets = new LinkedHashMap<>();
    private long nextTicketId = 1, nextVenueId = 1;
    private Path file;
    private boolean available = true;

    @PostConstruct void load() {
        file = Path.of(System.getenv().getOrDefault("TICKET_DATA_FILE", "tickets.json")).toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file); JsonReader reader = Json.createReader(in)) {
                    JsonObject data = reader.readObject();
                    nextTicketId = data.getJsonNumber("nextTicketId").longValueExact();
                    nextVenueId = data.getJsonNumber("nextVenueId").longValueExact();
                    for (JsonValue v : data.getJsonArray("items")) {
                        JsonObject t = v.asJsonObject(); tickets.put(t.getInt("id"),t);
                    }
                }
            }
        } catch (Exception e) {
            available = false;
            Logger.getLogger(getClass().getName()).severe("Не удалось открыть хранилище: " + e.getMessage());
        }
    }
    private void ready() { if (!available) throw new Fault(503,"Хранилище коллекции недоступно"); }
    public synchronized List<JsonObject> all() { ready(); return new ArrayList<>(tickets.values()); }
    public synchronized JsonObject get(int id) {
        ready(); JsonObject ticket = tickets.get(id);
        if (ticket == null) throw new Fault(404,"Билет с id = " + id + " не найден");
        return ticket;
    }
    public synchronized JsonObject create(JsonObject input) {
        ready();
        if (nextTicketId > Integer.MAX_VALUE || nextVenueId > Integer.MAX_VALUE)
            throw new Fault(503,"Исчерпаны идентификаторы коллекции");
        JsonObject ticket = Json.createObjectBuilder(input).add("id",nextTicketId).add("creationDate",Api.now())
            .add("venue",Json.createObjectBuilder(input.getJsonObject("venue")).add("id",nextVenueId)).build();
        Map<Integer,JsonObject> updated = new LinkedHashMap<>(tickets);
        updated.put((int)nextTicketId,ticket);
        save(updated,nextTicketId+1,nextVenueId+1); return ticket;
    }
    public synchronized JsonObject update(int id, JsonObject expected, JsonObject input) {
        ready();
        // Сравниваем снимок, полученный в начале PUT, с текущим объектом.
        // JSON-объекты неизменяемы; ссылка меняется при каждом успешном обновлении.
        if (tickets.get(id) != expected) throw new Fault(409,"Билет был изменён или удалён параллельным запросом");
        JsonObject ticket = Json.createObjectBuilder(input).add("id",id).add("creationDate",expected.getString("creationDate"))
            .add("venue",Json.createObjectBuilder(input.getJsonObject("venue")).add("id",expected.getJsonObject("venue").getInt("id"))).build();
        Map<Integer,JsonObject> updated = new LinkedHashMap<>(tickets); updated.put(id,ticket);
        save(updated,nextTicketId,nextVenueId); return ticket;
    }
    public synchronized void delete(int id) {
        JsonObject ticket = get(id); free(ticket);
        Map<Integer,JsonObject> updated = new LinkedHashMap<>(tickets); updated.remove(id);
        save(updated,nextTicketId,nextVenueId);
    }
    public synchronized JsonObject deletePrice(float price) {
        ready(); boolean found = false;
        for (JsonObject ticket : tickets.values()) {
            if ((float)ticket.getJsonNumber("price").doubleValue() != price) continue;
            found = true;
            if (ticket.isNull("personId")) { delete(ticket.getInt("id")); return ticket; }
        }
        throw new Fault(found ? 409 : 404,found ? "Все билеты с указанной ценой забронированы" : "Билет с указанной ценой не найден");
    }
    private void free(JsonObject ticket) {
        if (!ticket.isNull("personId")) throw new Fault(409,"Билет забронирован; сначала отмените бронирование");
    }
    private void save(Map<Integer,JsonObject> updated, long ticketId, long venueId) {
        JsonArrayBuilder items = Json.createArrayBuilder(); updated.values().forEach(items::add);
        JsonObject data = Json.createObjectBuilder().add("nextTicketId",ticketId).add("nextVenueId",venueId).add("items",items).build();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParent(),"tickets-",".tmp");
            try (OutputStream out = Files.newOutputStream(temporary); JsonWriter writer = Json.createWriter(out)) { writer.writeObject(data); }
            Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            tickets = updated; nextTicketId = ticketId; nextVenueId = venueId;
        } catch (IOException e) { throw new Fault(503,"Не удалось сохранить коллекцию"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
}
