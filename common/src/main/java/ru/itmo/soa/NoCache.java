package ru.itmo.soa;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Запрет кеширования действует и на REST API, и на статические файлы. */
@WebFilter("/*")
public class NoCache implements Filter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((HttpServletResponse) response).setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
