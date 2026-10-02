package com.example.flights.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers declare a {@link StationScope} parameter that is filled
 * from the authenticated JWT. Requests without a JWT get an empty scope.
 */
public class StationScopeArgumentResolver implements HandlerMethodArgumentResolver {

    private final StationScopeResolver resolver;

    public StationScopeArgumentResolver(StationScopeResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return StationScope.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return resolver.resolve(token.getToken());
        }
        return StationScope.none();
    }
}
