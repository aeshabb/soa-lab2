package ru.itmo.soa;

import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class Errors implements ExceptionMapper<Exception> {
    @Context UriInfo uri;
    private static final Map<Integer, String> LABELS = Map.ofEntries(
        Map.entry(400,"Bad Request"), Map.entry(404,"Not Found"), Map.entry(405,"Method Not Allowed"),
        Map.entry(406,"Not Acceptable"), Map.entry(409,"Conflict"), Map.entry(415,"Unsupported Media Type"),
        Map.entry(422,"Unprocessable Entity"), Map.entry(500,"Internal Server Error"),
        Map.entry(502,"Bad Gateway"), Map.entry(503,"Service Unavailable"), Map.entry(504,"Gateway Timeout"));
    @Override public Response toResponse(Exception e) {
        int status = e instanceof Fault f ? f.status : e instanceof WebApplicationException w ? w.getResponse().getStatus() : 500;
        if (status == 500) Logger.getLogger(Errors.class.getName()).log(Level.SEVERE, "Ошибка обработки запроса", e);
        String message = e instanceof Fault ? e.getMessage() : status == 500 ? "Внутренняя ошибка сервиса" : LABELS.getOrDefault(status,"Ошибка запроса");
        JsonObjectBuilder result = Json.createObjectBuilder().add("timestamp", Api.now()).add("status",status)
                .add("error",LABELS.getOrDefault(status,"Error")).add("message",message)
                .add("path",uri.getRequestUri().getPath());
        if (e instanceof Fault f && f.violations != null) result.add("violations",f.violations);
        return Api.json(status,result.build());
    }
}
