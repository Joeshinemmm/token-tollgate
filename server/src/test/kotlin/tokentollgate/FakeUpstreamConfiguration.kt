package tokentollgate

import fakeprovider.FakeProperties
import fakeprovider.anthropic.AnthropicFakeController
import fakeprovider.auth.ApiKeyFilter
import fakeprovider.common.RandomFailures
import fakeprovider.common.ResponseWriter
import fakeprovider.directive.DirectiveParser
import fakeprovider.error.FakeErrorHandler
import fakeprovider.openai.OpenAiFakeController
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.Order
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.reactive.config.PathMatchConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer

/**
 * Mounts the fake provider inside the gateway's test application under `/fake`, so that integration tests have an
 * upstream without a second process. Its controllers are registered explicitly (their package is outside the
 * gateway's component scan), prefixed by package, and left open by a security chain that matches before the others.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(
	OpenAiFakeController::class,
	AnthropicFakeController::class,
	FakeErrorHandler::class,
	DirectiveParser::class,
	RandomFailures::class,
	ResponseWriter::class,
	ApiKeyFilter::class,
)
@EnableConfigurationProperties(FakeProperties::class)
class FakeUpstreamConfiguration {

	@Bean
	fun fakePathPrefix(): WebFluxConfigurer = object : WebFluxConfigurer {
		override fun configurePathMatching(configurer: PathMatchConfigurer) {
			configurer.addPathPrefix("/fake", HandlerTypePredicate.forBasePackage("fakeprovider"))
		}
	}

	@Bean
	@Order(0)
	fun fakeUpstreamChain(http: ServerHttpSecurity): SecurityWebFilterChain = http
		.securityMatcher(PathPatternParserServerWebExchangeMatcher("/fake/**"))
		.csrf { it.disable() }
		.httpBasic { it.disable() }
		.formLogin { it.disable() }
		.logout { it.disable() }
		.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
		.authorizeExchange { it.anyExchange().permitAll() }
		.build()
}
