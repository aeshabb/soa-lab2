package ru.itmo.soa.tickets;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import ru.itmo.soa.Errors;
import ru.itmo.soa.NoCache;

@ApplicationPath("/")
public class TicketApplication extends Application {
    @Override public Set<Class<?>> getClasses() { return Set.of(Tickets.class, Errors.class, NoCache.class); }
}
