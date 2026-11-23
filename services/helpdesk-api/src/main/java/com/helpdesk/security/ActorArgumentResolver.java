package com.helpdesk.security;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets controller methods simply declare an {@link Actor} parameter, e.g.
 * {@code reply(@PathVariable long id, Actor actor, @RequestBody ReplyRequest body)}.
 * The actor is resolved from the security context (the verified token), never from input.
 */
@Configuration
public class ActorArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final AgentDirectory agents;

    ActorArgumentResolver(AgentDirectory agents) {
        this.agents = agents;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Actor.class);
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return agents.resolve(SecurityContextHolder.getContext().getAuthentication());
    }
}
