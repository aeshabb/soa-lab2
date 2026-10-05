package ru.itmo.soa.booking;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.IOException;

@Path("/")
public class ClientPage {
    @Context ServletContext context;
    @GET @Produces("text/html") public Response index() throws IOException { return file("index.html","text/html; charset=utf-8"); }
    @GET @Path("app.js") @Produces("text/javascript") public Response js() throws IOException { return file("app.js","text/javascript; charset=utf-8"); }
    @GET @Path("style.css") @Produces("text/css") public Response css() throws IOException { return file("style.css","text/css; charset=utf-8"); }
    private Response file(String name, String type) throws IOException {
        try (var in = context.getResourceAsStream("/"+name)) { return Response.ok(in.readAllBytes(),type).build(); }
    }
}
