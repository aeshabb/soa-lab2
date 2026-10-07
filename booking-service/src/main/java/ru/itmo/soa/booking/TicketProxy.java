package ru.itmo.soa.booking;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.http.HttpResponse;

// Браузер обращается к одному HTTPS-адресу. Payara передаёт запросы первого API
// в WildFly по HTTPS с проверкой сертификата; CORS и второй browser origin не нужны.
@Path("/client-api/tickets") @Produces("application/json")
public class TicketProxy {
    @Inject TicketClient tickets;
    @Context UriInfo uri;
    private Response relay(String method, String tail, String body, String type) {
        String query = uri.getRequestUri().getRawQuery();
        HttpResponse<String> upstream = tickets.request(method,"/tickets" + tail + (query == null ? "" : "?"+query),body,type);
        Response.ResponseBuilder response = Response.status(upstream.statusCode());
        if (upstream.statusCode() != 204) response.type("application/json").entity(upstream.body());
        upstream.headers().firstValue("Location").ifPresent(value -> response.header("Location",value));
        return response.build();
    }
    @GET public Response list() { return relay("GET","",null,null); }
    @POST public Response create(String body,@HeaderParam("Content-Type") String type) { return relay("POST","",body,type); }
    @GET @Path("/{tail: .+}") public Response get(@Encoded @PathParam("tail") String tail) { return relay("GET","/"+tail,null,null); }
    @PUT @Path("/{tail: .+}") public Response update(@Encoded @PathParam("tail") String tail,String body,@HeaderParam("Content-Type") String type) { return relay("PUT","/"+tail,body,type); }
    @DELETE @Path("/{tail: .+}") public Response delete(@Encoded @PathParam("tail") String tail) { return relay("DELETE","/"+tail,null,null); }
}
