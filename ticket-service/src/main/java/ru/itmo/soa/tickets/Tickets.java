package ru.itmo.soa.tickets;

import jakarta.inject.Inject;
import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.util.*;
import ru.itmo.soa.*;

@Path("/tickets") @Produces("application/json")
public class Tickets {
    @Inject TicketStore store;
    @GET public Response list(@Context UriInfo uri) { return Api.json(200,Selection.page(store.all(),uri.getQueryParameters())); }
    @POST public Response create(String body, @HeaderParam("Content-Type") String type) {
        Api.contentType(type); JsonObject ticket = store.create(Validation.ticket(Api.object(body)));
        return Response.created(URI.create("/tickets/" + ticket.getInt("id"))).type("application/json").entity(ticket.toString()).build();
    }
    @GET @Path("/{id}") public Response get(@PathParam("id") String id) { return Api.json(200,store.get(Api.positiveId(id,"id"))); }
    @PUT @Path("/{id}") public Response update(@PathParam("id") String raw, String body, @HeaderParam("Content-Type") String type) {
        Api.contentType(type); int id = Api.positiveId(raw,"id"); JsonObject expected = store.get(id);
        JsonObject input = Validation.ticket(Api.object(body)); return Api.json(200,store.update(id,expected,input));
    }
    @DELETE @Path("/{id}") public Response delete(@PathParam("id") String id) {
        store.delete(Api.positiveId(id,"id")); return Response.noContent().build();
    }
    @DELETE @Path("/price/{price}") public Response deletePrice(@PathParam("price") String price) { return Api.json(200,store.deletePrice(Api.price(price))); }
    @GET @Path("/price/average") public Response average() {
        List<JsonObject> all = store.all();
        double average = all.stream().mapToDouble(t -> t.getJsonNumber("price").doubleValue()).average().orElse(0);
        return Api.json(200,Json.createObjectBuilder().add("average",average).add("count",all.size()).build());
    }
    @GET @Path("/comment/min") public Response minimum() {
        return Api.json(200,store.all().stream().filter(t -> !t.isNull("comment"))
                .min(Comparator.comparing(t -> t.getString("comment")))
                .orElseThrow(() -> new Fault(404,"В коллекции нет билетов с ненулевым полем comment")));
    }
}
