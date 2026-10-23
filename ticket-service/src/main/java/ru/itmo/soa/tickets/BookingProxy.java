package ru.itmo.soa.tickets;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.*;
import ru.itmo.soa.Fault;

// Клиент остаётся на WildFly; только операции бронирования требуют Payara.
@ApplicationScoped
@Path("/client-api/booking") @Produces("application/json")
public class BookingProxy {
    private HttpClient client;
    private String base;

    @PostConstruct void init() {
        base = System.getenv("BOOKING_SERVICE_URL");
        if (base == null || !"https".equals(URI.create(base).getScheme()))
            throw new IllegalStateException("Booking Service должен использовать HTTPS");
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream in = Files.newInputStream(java.nio.file.Path.of(System.getenv("BOOKING_CERTIFICATE")))) {
                trust.setCertificateEntry("booking", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось загрузить сертификат Booking Service", e);
        }
    }

    @POST @Path("/sell/vip/{ticket-id}/{person-id}")
    public Response sell(@Encoded @PathParam("ticket-id") String ticket, @Encoded @PathParam("person-id") String person) {
        return relay("/booking/sell/vip/" + ticket + "/" + person);
    }

    @POST @Path("/person/{person-id}/cancel")
    public Response cancel(@Encoded @PathParam("person-id") String person) {
        return relay("/booking/person/" + person + "/cancel");
    }

    private Response relay(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> upstream = client.send(request, HttpResponse.BodyHandlers.ofString());
            Response.ResponseBuilder response = Response.status(upstream.statusCode());
            if (upstream.statusCode() != 204) response.type("application/json").entity(upstream.body());
            upstream.headers().firstValue("Location").ifPresent(value -> response.header("Location", value));
            return response.build();
        } catch (HttpTimeoutException e) {
            throw new Fault(504, "Сервис бронирования не ответил за отведённое время");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fault(502, "Сервис бронирования временно недоступен");
        } catch (java.io.IOException e) {
            throw new Fault(502, "Сервис бронирования временно недоступен");
        }
    }
}
