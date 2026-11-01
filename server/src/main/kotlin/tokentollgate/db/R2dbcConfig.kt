package tokentollgate.db

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.converter.ConverterFactory
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.DialectResolver

@Configuration(proxyBeanMethods = false)
class R2dbcConfig {

	@Bean
	fun r2dbcCustomConversions(connectionFactory: ConnectionFactory): R2dbcCustomConversions {
		val dialect = DialectResolver.getDialect(connectionFactory)
		return R2dbcCustomConversions.of(dialect, listOf(DbEnumWritingConverter(), DbEnumReadingConverterFactory()))
	}
}

@WritingConverter
class DbEnumWritingConverter : Converter<DbEnum, String> {
	override fun convert(source: DbEnum): String = source.dbValue
}

@ReadingConverter
class DbEnumReadingConverterFactory : ConverterFactory<String, DbEnum> {
	override fun <T : DbEnum> getConverter(targetType: Class<T>): Converter<String, T> {
		val constants = requireNotNull(targetType.enumConstants) { "${targetType.name} is not an enum" }
		return Converter { source ->
			constants.firstOrNull { it.dbValue == source }
				?: throw IllegalArgumentException("Unknown ${targetType.simpleName} value: $source")
		}
	}
}
