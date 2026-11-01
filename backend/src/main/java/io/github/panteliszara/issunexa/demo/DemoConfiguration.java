package io.github.panteliszara.issunexa.demo;

import org.apache.catalina.Session;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {
    public DemoConfiguration(Environment environment) {
        if (!environment.matchesProfiles("public"))
            throw new IllegalArgumentException("Demo requires the public profile and a dedicated synthetic database.");
    }

    @Bean
    @DependsOn("entityManagerFactory")
    public InitializingBean demoDatabaseGuard(JdbcTemplate jdbc) {
        return () -> {
            if (jdbc.queryForObject("SELECT count(*) FROM users WHERE demo_workspace_id IS NULL OR role = 'ADMIN'", Long.class) != 0
                    || jdbc.queryForObject("SELECT count(*) FROM demo_workspaces", Long.class) > 50
                    || Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM demo_workspaces w LEFT JOIN users u ON u.demo_workspace_id = w.id
                            GROUP BY w.id HAVING count(u.id) <> 2 OR count(*) FILTER (WHERE u.role = 'REQUESTER') <> 1
                                OR count(*) FILTER (WHERE u.role = 'AGENT') <> 1)
                            OR EXISTS (SELECT 1 FROM tickets t LEFT JOIN users u ON u.id = t.requester_id
                                WHERE u.demo_workspace_id IS NULL)
                        """, Boolean.class)))
                throw new IllegalStateException("Demo requires a dedicated, consistent synthetic database.");
        };
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> demoSessionLimit() {
        return factory -> factory.addContextCustomizers(context -> {
            StandardManager manager = new StandardManager() {
                @Override
                public synchronized Session createSession(String sessionId) {
                    return super.createSession(sessionId);
                }
            };
            manager.setMaxActiveSessions(100);
            manager.setPathname(null);
            context.setManager(manager);
        });
    }

    @Bean
    public FilterRegistrationBean<Filter> demoSessionExhaustionHandler() {
        Filter filter = (request, response, chain) -> {
            try {
                chain.doFilter(request, response);
            } catch (RuntimeException | ServletException exception) {
                Throwable cause = exception;
                while (cause != null && !(cause instanceof TooManyActiveSessionsException)) cause = cause.getCause();
                if (cause == null) throw exception;
                DemoHttpResponses.unavailable((jakarta.servlet.http.HttpServletResponse) response);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }
}
