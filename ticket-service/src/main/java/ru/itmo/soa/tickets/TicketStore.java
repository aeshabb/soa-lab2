package ru.itmo.soa.tickets;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;
import ru.itmo.soa.*;

@ApplicationScoped
public class TicketStore {
    private Map<Integer, JsonObject> tickets = new LinkedHashMap<>();
    private long nextTicketId = 1, nextVenueId = 1;
    private String databaseUrl;
    private final Properties databaseProperties = new Properties();
    private boolean available = true;

    @PostConstruct void load() {
        try {
            databaseUrl = System.getenv("TICKET_DATABASE_URL");
            databaseProperties.setProperty("user",System.getenv("TICKET_DATABASE_USER"));
            // Драйвер внутри WAR регистрируем в загрузчике приложения.
            // Пароль pgJDBC читает из ~/.pgpass пользователя Helios.
            Class.forName("org.postgresql.Driver");
            try (Connection connection = connect(); Statement query = connection.createStatement()) {
                try (ResultSet state = query.executeQuery("SELECT next_ticket_id, next_venue_id FROM soa_lab2_state WHERE id = 1")) {
                    if (!state.next()) throw new SQLException("Не настроены счётчики коллекции");
                    nextTicketId = state.getLong(1); nextVenueId = state.getLong(2);
                }
                try (ResultSet rows = query.executeQuery("SELECT t.*, v.name AS venue_name, v.capacity AS venue_capacity, v.type AS venue_type FROM soa_lab2_tickets t JOIN soa_lab2_venues v ON v.id = t.venue_id ORDER BY t.id")) {
                    while (rows.next()) {
                        String comment = rows.getString("comment"), venueType = rows.getString("venue_type");
                        Integer person = rows.getObject("person_id",Integer.class);
                        JsonObject ticket = Json.createObjectBuilder().add("id",rows.getInt("id"))
                            .add("name",rows.getString("name"))
                            .add("coordinates",Json.createObjectBuilder().add("x",rows.getInt("coordinate_x")).add("y",rows.getDouble("coordinate_y")))
                            .add("creationDate",rows.getObject("creation_date",LocalDateTime.class).format(Api.DATE))
                            .add("price",(double)rows.getFloat("price"))
                            .add("comment",comment == null ? JsonValue.NULL : Json.createValue(comment))
                            .add("type",rows.getString("type"))
                            .add("venue",Json.createObjectBuilder().add("id",rows.getInt("venue_id"))
                                .add("name",rows.getString("venue_name")).add("capacity",rows.getInt("venue_capacity"))
                                .add("type",venueType == null ? JsonValue.NULL : Json.createValue(venueType)))
                            .add("personId",person == null ? JsonValue.NULL : Json.createValue(person)).build();
                        tickets.put(ticket.getInt("id"),ticket);
                    }
                }
            }
        } catch (Exception e) {
            available = false;
            Logger.getLogger(getClass().getName()).severe("Не удалось открыть хранилище: " + e.getMessage());
        }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(databaseUrl,databaseProperties); }
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
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement venue = connection.prepareStatement("INSERT INTO soa_lab2_venues (id, name, capacity, type) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, capacity = EXCLUDED.capacity, type = EXCLUDED.type");
                 PreparedStatement write = connection.prepareStatement("INSERT INTO soa_lab2_tickets (id, name, coordinate_x, coordinate_y, creation_date, price, comment, type, venue_id, person_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, coordinate_x = EXCLUDED.coordinate_x, coordinate_y = EXCLUDED.coordinate_y, price = EXCLUDED.price, comment = EXCLUDED.comment, type = EXCLUDED.type, person_id = EXCLUDED.person_id");
                 PreparedStatement remove = connection.prepareStatement("DELETE FROM soa_lab2_tickets WHERE id = ?");
                 PreparedStatement removeVenue = connection.prepareStatement("DELETE FROM soa_lab2_venues WHERE id = ?");
                 PreparedStatement state = connection.prepareStatement("UPDATE soa_lab2_state SET next_ticket_id = ?, next_venue_id = ? WHERE id = 1")) {
                for (var entry : updated.entrySet()) {
                    if (tickets.get(entry.getKey()) == entry.getValue()) continue;
                    JsonObject ticket = entry.getValue(), place = ticket.getJsonObject("venue"), coordinates = ticket.getJsonObject("coordinates");
                    venue.setInt(1,place.getInt("id")); venue.setString(2,place.getString("name"));
                    venue.setInt(3,place.getInt("capacity")); venue.setString(4,place.isNull("type") ? null : place.getString("type")); venue.addBatch();
                    write.setInt(1,entry.getKey()); write.setString(2,ticket.getString("name"));
                    write.setInt(3,coordinates.getInt("x")); write.setDouble(4,coordinates.getJsonNumber("y").doubleValue());
                    write.setTimestamp(5,Timestamp.valueOf(LocalDateTime.parse(ticket.getString("creationDate"),Api.DATE)));
                    write.setFloat(6,(float)ticket.getJsonNumber("price").doubleValue());
                    write.setString(7,ticket.isNull("comment") ? null : ticket.getString("comment")); write.setString(8,ticket.getString("type"));
                    write.setInt(9,place.getInt("id"));
                    if (ticket.isNull("personId")) write.setNull(10,Types.INTEGER); else write.setInt(10,ticket.getInt("personId"));
                    write.addBatch();
                }
                venue.executeBatch();
                write.executeBatch();
                for (int id : tickets.keySet()) {
                    if (updated.containsKey(id)) continue;
                    remove.setInt(1,id); remove.addBatch();
                    removeVenue.setInt(1,tickets.get(id).getJsonObject("venue").getInt("id")); removeVenue.addBatch();
                }
                remove.executeBatch();
                removeVenue.executeBatch();
                state.setLong(1,ticketId); state.setLong(2,venueId);
                if (state.executeUpdate() != 1) throw new SQLException("Не найдены счётчики коллекции");
                connection.commit();
                tickets = updated; nextTicketId = ticketId; nextVenueId = venueId;
            } catch (SQLException e) { connection.rollback(); throw e; }
        } catch (SQLException e) {
            Logger.getLogger(getClass().getName()).warning("Не удалось сохранить коллекцию в PostgreSQL: " + e.getMessage());
            throw new Fault(503,"Не удалось сохранить коллекцию в PostgreSQL");
        }
    }
}
