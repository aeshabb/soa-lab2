package ru.itmo.soa.booking;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import ru.itmo.soa.Errors;
import ru.itmo.soa.NoCache;

@ApplicationPath("/")
public class BookingApplication extends Application {
    @Override public Set<Class<?>> getClasses() { return Set.of(Booking.class, Errors.class, NoCache.class); }
}
