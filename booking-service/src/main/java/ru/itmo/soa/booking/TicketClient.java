package ru.itmo.soa.booking;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonObject;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.*;
import ru.itmo.soa.*;

@ApplicationScoped
public class TicketClient {
    private HttpClient client;
    private String base;
    @PostConstruct void init() {
        base = System.getenv().getOrDefault("TICKET_SERVICE_URL", "https://localhost:18443");
        if (!URI.create(base).getScheme().equals("https")) throw new IllegalStateException("Ticket Service должен использовать HTTPS");
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(System.getenv("TICKET_TRUSTSTORE")))) {
                trust.load(in,System.getenv("TICKET_TRUSTSTORE_PASSWORD").toCharArray());
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS"); context.init(null,factory.getTrustManagers(),null);
            client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        } catch (Exception e) { throw new IllegalStateException("Не удалось загрузить сертификат Ticket Service",e); }
    }
    public URI ticketUri(int id) { return URI.create(base + "/tickets/" + id); }
    public HttpResponse<String> request(String method, String path, String body, String type) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if (type != null) request.header("Content-Type",type);
        request.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
        catch (HttpTimeoutException e) { throw new Fault(504,"Ticket Service не ответил за отведённое время"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Fault(502,"Вызов Ticket Service был прерван"); }
        catch (java.io.IOException e) { throw new Fault(502,"Ticket Service недоступен"); }
    }
    public JsonObject operation(String method, String path, JsonObject body, int expected) {
        HttpResponse<String> response = request(method,path,body == null ? null : body.toString(),body == null ? null : "application/json");
        int status = response.statusCode();
        if (status == 409) throw new Fault(409,"Операция конфликтует с текущим состоянием Ticket Service");
        if (status == 404 && method.equals("PUT")) throw new Fault(409,"Билет был удалён во время отмены бронирования");
        if (status == 404 && method.equals("GET") && !path.contains("?")) throw new Fault(404,"Билет не найден в коллекции первого сервиса");
        if (status != expected) throw new Fault(502,"Ticket Service вернул ошибку HTTP " + status);
        try { return Api.object(response.body()); }
        catch (Fault e) { throw new Fault(502,"Ticket Service вернул некорректный JSON"); }
    }
}
