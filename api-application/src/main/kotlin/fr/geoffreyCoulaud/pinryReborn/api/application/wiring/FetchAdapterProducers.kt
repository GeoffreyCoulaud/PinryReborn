package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.fetch.http.AddressPolicy
import fr.geoffreyCoulaud.pinryReborn.api.fetch.http.HttpImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImageDownloadConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [ImageFetcher] in the composition root: only this module may depend on the
 * `api-fetch-http` adapter. The SSRF address policy is chosen from config: the Standard guard by
 * default, or AllowAll when `images.download.allow_private_addresses=true` (trusted networks / tests).
 */
@ApplicationScoped
class FetchAdapterProducers {
    @Produces
    @ApplicationScoped
    fun imageFetcher(config: ImageDownloadConfig): ImageFetcher {
        val policy = if (config.allowPrivateAddresses()) AddressPolicy.AllowAll else AddressPolicy.Standard
        return HttpImageFetcher(config.connectTimeout(), config.requestTimeout(), config.maxRedirects(), policy)
    }
}
