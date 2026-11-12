package tokentollgate.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
import tokentollgate.gateway.ProtocolErrorWriter

/**
 * Three stateless chains, matched in order:
 * `/admin/...` needs the admin token, `/v1/...` needs a virtual key, and everything else is denied
 * except the health endpoint. No sessions, no CSRF, no form or basic login.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig(
	private val authenticationManager: ReactiveAuthenticationManager,
	private val errorWriter: ProtocolErrorWriter,
) {

	@Bean
	@Order(1)
	fun adminChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		statelessChain(http, "/admin/**", AdminTokenConverter(), AdminEntryPoint(errorWriter))
			.authorizeExchange { it.anyExchange().hasAuthority(ROLE_ADMIN) }
			.build()

	@Bean
	@Order(2)
	fun gatewayChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		statelessChain(http, "/v1/**", VirtualKeyConverter(), GatewayEntryPoint(errorWriter))
			.authorizeExchange { it.anyExchange().hasAuthority(ROLE_GATEWAY_KEY) }
			.build()

	@Bean
	@Order(3)
	fun defaultChain(http: ServerHttpSecurity): SecurityWebFilterChain =
		http
			.disableSessionAndLogins()
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.authorizeExchange {
				it.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
				it.anyExchange().denyAll()
			}
			.build()

	private fun statelessChain(
		http: ServerHttpSecurity,
		pattern: String,
		converter: ServerAuthenticationConverter,
		entryPoint: ServerAuthenticationEntryPoint,
	): ServerHttpSecurity {
		val filter = AuthenticationWebFilter(authenticationManager).apply {
			setServerAuthenticationConverter(converter)
			setAuthenticationFailureHandler(ServerAuthenticationEntryPointFailureHandler(entryPoint))
			setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance())
		}
		return http
			.securityMatcher(PathPatternParserServerWebExchangeMatcher(pattern))
			.disableSessionAndLogins()
			.addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
			.exceptionHandling { it.authenticationEntryPoint(entryPoint) }
	}

	private fun ServerHttpSecurity.disableSessionAndLogins(): ServerHttpSecurity =
		csrf { it.disable() }
			.httpBasic { it.disable() }
			.formLogin { it.disable() }
			.logout { it.disable() }
			.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
}
