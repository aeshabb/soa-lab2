package ru.itmo.soa.booking;

import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.util.*;
import ru.itmo.soa.*;

@Path("/booking") @Produces("application/json")
public class Booking {
    @Inject TicketClient tickets;
    @POST @Path("/sell/vip/{ticket-id}/{person-id}")
    public Response sell(@PathParam("ticket-id") String rawTicket, @PathParam("person-id") String rawPerson) {
        int id = Api.positiveId(rawTicket,"ticket-id"), person = Api.positiveId(rawPerson,"person-id");
        JsonObject source = tickets.operation("GET","/tickets/"+id,null,200);
        JsonObject input;
        JsonNumber originalPrice;
        try { input = Api.input(source); originalPrice = source.getJsonNumber("price"); }
        catch (RuntimeException e) { throw new Fault(502,"Ticket Service вернул некорректный билет"); }
        if (originalPrice == null) throw new Fault(502,"Ticket Service вернул некорректную цену");
        float price = originalPrice.bigDecimalValue().floatValue() * 2;
        if (!Float.isFinite(price) || price <= 0) {
            JsonArrayBuilder violations = Json.createArrayBuilder();
            Validation.violation(violations,"price","удвоенная цена выходит за диапазон float",originalPrice);
            throw new Fault(422,"Удвоенная цена билета выходит за пределы допустимых значений",violations.build());
        }
        input = Json.createObjectBuilder(input).add("price",(double)price).add("type","VIP").add("personId",person).build();
        JsonObject created = tickets.operation("POST","/tickets",input,201);
        if (!created.containsKey("id")) throw new Fault(502,"Ticket Service не вернул идентификатор билета");
        return Response.created(tickets.ticketUri(created.getInt("id"))).type("application/json").entity(created.toString()).build();
    }
    @POST @Path("/person/{person-id}/cancel")
    public Response cancel(@PathParam("person-id") String rawPerson) {
        int person = Api.positiveId(rawPerson,"person-id");
        // Сначала читаем все страницы, затем обновляем: изменение personId во время
        // постраничной выборки уменьшило бы коллекцию и пропустило часть билетов.
        List<JsonObject> found = new ArrayList<>();
        int page = 1, pages;
        do {
            JsonObject result = tickets.operation("GET","/tickets?personId="+person+"&size=100&page="+page,null,200);
            try {
                for (JsonValue item : result.getJsonArray("items")) found.add(item.asJsonObject());
                pages = result.getInt("totalPages");
            } catch (RuntimeException e) { throw new Fault(502,"Ticket Service вернул некорректную страницу"); }
            page++;
        } while (page <= pages);
        JsonArrayBuilder ids = Json.createArrayBuilder();
        for (JsonObject ticket : found) {
            JsonObject input;
            try { input = Json.createObjectBuilder(Api.input(ticket)).addNull("personId").build(); }
            catch (RuntimeException e) { throw new Fault(502,"Ticket Service вернул некорректный билет"); }
            int id = ticket.getInt("id");
            tickets.operation("PUT","/tickets/"+id,input,200); ids.add(id);
        }
        return Api.json(200,Json.createObjectBuilder().add("personId",person).add("cancelledCount",found.size()).add("cancelledTicketIds",ids).build());
    }
}
