package ru.itmo.soa.tickets;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import ru.itmo.soa.Errors;

@ApplicationPath("/client-api")
public class ClientApiApplication extends Application {
    @Override public Set<Class<?>> getClasses() { return Set.of(BookingProxy.class, Errors.class); }
}
