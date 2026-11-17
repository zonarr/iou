package io.github.zonarr.iou.config;

import io.github.zonarr.iou.auth.Actor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Interactive API documentation: Swagger UI at {@code /swagger-ui.html}, the OpenAPI
 * document at {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer-jwt";

    static {
        // Actor is filled in from the login token, it is not something a client sends.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class);
    }

    @Bean
    OpenAPI iouOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("iou API")
                        .version("0.1.0")
                        .description("""
                                Track who owes whom, in groups or one-on-one, and settle up with as few \
                                payments as possible.

                                **Trying it here:** call `POST /api/auth/register` or `POST /api/auth/login`, \
                                copy the `token` from the answer, press **Authorize** and paste it.

                                **Money:** every amount has two decimal places. Each expense, debt and \
                                payment carries its original amount and currency, the exchange rate to THB \
                                that was typed in, and the resulting THB amount. Balances and settle-up \
                                are always in THB.

                                **Confirmation:** a debt counts once the debtor accepts it, a payment \
                                once the receiver confirms it. Entries are confirmed immediately when \
                                recorded by that person, when that person is a placeholder, or in a \
                                trusted group.""")
                        .license(new License().name("MIT").url("https://opensource.org/license/mit")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The token returned by register or login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
