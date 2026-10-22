package ru.itmo.soa;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** A cached success must never hide an unavailable service. */
@Provider
public class NoCache implements ContainerResponseFilter {
    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Cache-Control", "no-store");
    }
}
