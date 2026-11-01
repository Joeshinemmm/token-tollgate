package tokentollgate.pricing

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Imports the price file named by `TOLLGATE_PRICES_FILE`, if any, once the application has started. */
@Component
class PriceSeeder(
	private val properties: PricingProperties,
	private val pricing: PricingService,
) : ApplicationRunner {

	private val log = LoggerFactory.getLogger(PriceSeeder::class.java)

	override fun run(args: ApplicationArguments) {
		val file = properties.file?.trim()?.takeIf { it.isNotEmpty() } ?: return
		val path = Path.of(file).toAbsolutePath()
		val result = runBlocking { pricing.importFile(path) }
		log.info("Price file {}: {} rows inserted, {} already present", path, result.inserted, result.skipped)
	}
}
