package fakeprovider.common

import fakeprovider.FakeProperties
import org.springframework.stereotype.Component
import kotlin.random.Random

/** `FAKE_ERROR_RATE`: fails that fraction of requests with 500 before anything else happens. */
@Component
class RandomFailures(properties: FakeProperties) {

	private val rate = properties.defaults.errorRate

	fun hit(): Boolean = rate > 0.0 && Random.nextDouble() < rate
}
